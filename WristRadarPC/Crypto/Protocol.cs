using System.Buffers.Binary;
using static WristRadarPC.Crypto.Prims;

namespace WristRadarPC.Crypto;

public interface ISigner
{
    byte[] PublicKey { get; }
    byte[] Sign(byte[] data);
}

/// <summary>Port of PairingCrypto.kt. The PC is always the initiator (A).</summary>
public sealed class PairingInitiator
{
    readonly ISigner _id;
    readonly byte[] _ephPriv = X25519Private();
    public byte[] EphPub { get; }
    public byte[] Nonce { get; } = Random(16);
    public byte[] MyId => _id.PublicKey;
    public byte[] Commit { get; }
    public byte[]? KemCiphertext { get; private set; }
    public byte[]? PeerId { get; private set; }
    byte[]? _kemSecret, _shared, _th, _th2;

    public PairingInitiator(ISigner id)
    {
        _id = id;
        EphPub = X25519Public(_ephPriv);
        Commit = Sha256(B("WR-COMMIT"), EphPub, MyId, Nonce);
    }

    public bool OnResponse(byte[] eph, byte[] id, byte[] n, byte[] kemPk)
    {
        if (eph.Length != 32 || id.Length != 32 || n.Length != 16 || kemPk.Length != MlKem.PublicKeySize) return false;
        if (CtEquals(id, MyId)) return false;
        try
        {
            _shared = X25519Agree(_ephPriv, eph);
            PeerId = id;
            _th = Sha256(B("WR-PAIR-V2"), Commit, eph, id, n, EphPub, MyId, Nonce);
            var (ct, ss) = MlKem.Encapsulate(kemPk);
            KemCiphertext = ct; _kemSecret = ss;
            _th2 = Sha256(B("WR-PAIR-PQ"), _th, kemPk, ct);
            return true;
        }
        catch { return false; }
    }

    public string SasCode()
    {
        var b = Hkdf(_shared!, _th!, "wr sas", 4);
        long v = ((long)b[0] << 24) | ((long)b[1] << 16) | ((long)b[2] << 8) | b[3];
        var code = (v % 1_000_000).ToString().PadLeft(6, '0');
        return code[..3] + " " + code[3..];
    }

    byte[] ConfirmData(bool initiatorRole) => Cat(B("WR-CONFIRM"), new[] { (byte)(initiatorRole ? 'A' : 'B') }, _th2!);
    public byte[] MyConfirmation() => _id.Sign(ConfirmData(true));
    public bool VerifyPeerConfirmation(byte[] sig) => EdVerify(PeerId!, sig, ConfirmData(false));
    public byte[] DerivePsk() => Hkdf(Cat(_shared!, _kemSecret!), _th2!, "wr psk v2");
    public void WipeAll() { Wipe(_ephPriv); Wipe(_shared); Wipe(_kemSecret); }
}

public sealed record SessionKeys(byte[] Send, byte[] Recv);

/// <summary>Port of HandshakeClient (hybrid SIGMA-I + PSK).</summary>
public sealed class HandshakeClient
{
    static readonly byte[] NonceS = Nonce(1), NonceC = Nonce(2);
    static byte[] Nonce(byte v) { var n = new byte[12]; n[11] = v; return n; }
    const int ServerFixed = 32 + 16 + MlKem.CiphertextSize;

    readonly ISigner _id; readonly byte[] _partner, _psk;
    readonly byte[] _eph = X25519Private();
    readonly byte[] _ephPub, _nonce = Random(16);
    readonly MlKem.KeyPair _kem = MlKem.Generate();

    public HandshakeClient(ISigner id, byte[] partnerId, byte[] psk)
    { _id = id; _partner = partnerId; _psk = psk; _ephPub = X25519Public(_eph); }

    public byte[] Hello() => Cat(_ephPub, _nonce, _kem.PublicKey);

    public (byte[] fin, SessionKeys keys)? OnServer(byte[] p)
    {
        try
        {
            if (p.Length < ServerFixed + 16) return null;
            var ephS = p[..32]; var nS = p[32..48]; var ct = p[48..ServerFixed]; var enc = p[ServerFixed..];
            var t1 = Cat(B("WR-HS-V2"), _ephPub, _nonce, _kem.PublicKey, ephS, nS, ct);
            var ikm = Cat(X25519Agree(_eph, ephS), MlKem.Decapsulate(_kem, ct), _psk);
            var h1 = Sha256(t1);
            var hs = Hkdf(ikm, h1, "wr hs key");
            var body = GcmOpen(hs, NonceS, enc, h1);
            if (body == null || body.Length != 96) return null;
            var idS = body[..32]; var sigS = body[32..];
            if (!CtEquals(idS, _partner) || !EdVerify(idS, sigS, Cat(B("WR-HS-S"), h1))) return null;
            var t2 = Cat(t1, enc);
            var h2 = Sha256(t2);
            var sigC = _id.Sign(Cat(B("WR-HS-C"), h2));
            var fin = GcmSeal(hs, NonceC, Cat(_id.PublicKey, sigC), h2);
            var th = Sha256(Cat(t2, fin));
            var keys = new SessionKeys(Hkdf(ikm, th, "wr c2s"), Hkdf(ikm, th, "wr s2c"));
            Wipe(ikm); Wipe(_eph);
            return (fin, keys);
        }
        catch { return null; }
    }
}

public enum RecordError { Auth, Replay }

/// <summary>Port of SecureChannel.kt for the client role.</summary>
public sealed class SecureChannel
{
    public const int RekeyRecords = 200;
    static readonly TimeSpan RekeyTime = TimeSpan.FromMinutes(10);
    byte[] _sKey, _rKey;
    int _sEpoch, _sInEpoch, _rEpoch;
    long _sCtr, _rLast = -1;
    DateTime _sEpochStart = DateTime.UtcNow;
    const byte SendDir = 0x43, RecvDir = 0x53; // client
    public RecordError LastError { get; private set; }
    readonly object _lock = new();

    public SecureChannel(SessionKeys k) { _sKey = (byte[])k.Send.Clone(); _rKey = (byte[])k.Recv.Clone(); }

    public byte[] Seal(byte[] plain)
    {
        lock (_lock)
        {
            if (_sInEpoch >= RekeyRecords || DateTime.UtcNow - _sEpochStart > RekeyTime)
            {
                var next = Hkdf(_sKey, null, "wr rekey"); Wipe(_sKey); _sKey = next;
                _sEpoch = (_sEpoch + 1) & 0xff; _sInEpoch = 0; _sEpochStart = DateTime.UtcNow;
            }
            long ctr = _sCtr++; _sInEpoch++;
            var header = new byte[9]; header[0] = (byte)_sEpoch; BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(1), ctr);
            return Cat(header, GcmSeal(_sKey, Nonce(SendDir, ctr), plain, header));
        }
    }

    public byte[]? Open(byte[] rec)
    {
        lock (_lock)
        {
            LastError = RecordError.Auth;
            if (rec.Length < 25) return null;
            int epoch = rec[0];
            long ctr = BinaryPrimitives.ReadInt64BigEndian(rec.AsSpan(1, 8));
            var header = rec[..9]; var ct = rec[9..];
            if (ctr <= _rLast)
            {
                if (epoch == _rEpoch && GcmOpen(_rKey, Nonce(RecvDir, ctr), ct, header) != null) LastError = RecordError.Replay;
                return null;
            }
            if (epoch == _rEpoch)
            {
                var p = GcmOpen(_rKey, Nonce(RecvDir, ctr), ct, header);
                if (p != null) _rLast = ctr;
                return p;
            }
            if (epoch == ((_rEpoch + 1) & 0xff))
            {
                var next = Hkdf(_rKey, null, "wr rekey");
                var p = GcmOpen(next, Nonce(RecvDir, ctr), ct, header);
                if (p != null) { Wipe(_rKey); _rKey = next; _rEpoch = epoch; _rLast = ctr; }
                return p;
            }
            return null;
        }
    }

    static byte[] Nonce(byte dir, long ctr)
    {
        var n = new byte[12]; n[0] = dir; BinaryPrimitives.WriteInt64BigEndian(n.AsSpan(4), ctr); return n;
    }

    public void Destroy() { lock (_lock) { Wipe(_sKey); Wipe(_rKey); } }
}

public static class Advert
{
    public static readonly Guid ServiceUuid = Guid.Parse("7b3f1a52-9c4e-4d0b-a6e1-5f2c8d9e0a17");
    public static readonly Guid Rx = Guid.Parse("7b3f1a53-9c4e-4d0b-a6e1-5f2c8d9e0a17");
    public static readonly Guid Tx = Guid.Parse("7b3f1a54-9c4e-4d0b-a6e1-5f2c8d9e0a17");
    public const long WindowMs = 10 * 60 * 1000L;

    public static long CurrentWindow() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / WindowMs;

    public static byte[] AdvertKeyFor(byte[] psk, byte[] advertiserId) => Hkdf(psk, advertiserId, "wr advert token");

    public static byte[] Token(byte[] advertKey, long window)
    {
        var w = new byte[8]; BinaryPrimitives.WriteInt64BigEndian(w, window);
        return Hmac(advertKey, w)[..8];
    }

    /// <summary>The 128-bit service UUID as it appears over the air (little-endian).</summary>
    public static byte[] ServiceUuidLe()
    {
        var be = Convert.FromHexString(ServiceUuid.ToString("N"));
        Array.Reverse(be);
        return be;
    }
}
