using System.Buffers.Binary;
using System.Text;

namespace WristRadarPC.Core;

/// <summary>Frame and message type bytes - identical to protocol/Wire.kt on the watch.</summary>
public static class F
{
    public const byte ProfileReq = 0x01, ProfileResp = 0x02;
    public const byte PairCommit = 0x10, PairResponse = 0x11, PairReveal = 0x12, PairConfirm = 0x13, PairAbort = 0x14;
    public const byte HsHello = 0x20, HsServer = 0x21, HsFinish = 0x22, HsReject = 0x23, Blackbox = 0x24;
    public const byte Secure = 0x30;
}

public static class A
{
    public const byte Text = 0x40, Ack = 0x41, Profile = 0x42, Status = 0x43;
    public const byte ImgStart = 0x44, ImgChunk = 0x45, ImgStatus = 0x46, Unpair = 0x47, ImgFail = 0x49, Typing = 0x4A;
    public const byte CallInvite = 0x50, CallAccept = 0x51, CallDecline = 0x52, CallEnd = 0x53, CallAudio = 0x54, SecurityNotice = 0x55;
}

/// <summary>Big-endian writer matching Java's DataOutputStream usage in Wire.kt.</summary>
public sealed class Out
{
    readonly MemoryStream _m = new();
    public Out B(byte v) { _m.WriteByte(v); return this; }
    public Out I(int v) { Span<byte> s = stackalloc byte[4]; BinaryPrimitives.WriteInt32BigEndian(s, v); _m.Write(s); return this; }
    public Out L(long v) { Span<byte> s = stackalloc byte[8]; BinaryPrimitives.WriteInt64BigEndian(s, v); _m.Write(s); return this; }
    public Out Raw(byte[] v) { _m.Write(v); return this; }
    public Out Bytes(byte[]? v) { I(v?.Length ?? -1); if (v != null) _m.Write(v); return this; }
    public Out Str(string v) => Bytes(Encoding.UTF8.GetBytes(v));
    public byte[] Build() => _m.ToArray();
}

public sealed class In
{
    readonly byte[] _d; int _p;
    public In(byte[] d, int offset = 0) { _d = d; _p = offset; }
    public bool HasMore => _p < _d.Length;
    void Need(int n) { if (_p + n > _d.Length) throw new EndOfStreamException(); }
    public byte B() { Need(1); return _d[_p++]; }
    public int I() { Need(4); var v = BinaryPrimitives.ReadInt32BigEndian(_d.AsSpan(_p)); _p += 4; return v; }
    public long L() { Need(8); var v = BinaryPrimitives.ReadInt64BigEndian(_d.AsSpan(_p)); _p += 8; return v; }
    public byte[] Raw(int n) { Need(n); var r = _d.AsSpan(_p, n).ToArray(); _p += n; return r; }
    public byte[]? Bytes(int max = 70_000) { int n = I(); if (n < 0) return null; if (n > max) throw new InvalidDataException(); return Raw(n); }
    public string Str(int max = 4096) => Encoding.UTF8.GetString(Bytes(max) ?? Array.Empty<byte>());
}

public static class Ids
{
    /// <summary>Random v4 UUID as 16 big-endian bytes (Java UUID layout).</summary>
    public static byte[] NewId()
    {
        var b = Crypto.Prims.Random(16);
        b[6] = (byte)((b[6] & 0x0f) | 0x40);
        b[8] = (byte)((b[8] & 0x3f) | 0x80);
        return b;
    }

    /// <summary>Same string form as java.util.UUID.toString().</summary>
    public static string ToStr(byte[] b)
    {
        var h = Convert.ToHexString(b).ToLowerInvariant();
        return $"{h[..8]}-{h[8..12]}-{h[12..16]}-{h[16..20]}-{h[20..]}";
    }

    public static byte[] FromStr(string s) => Convert.FromHexString(s.Replace("-", ""));

    public static byte[] Frame(byte type, byte[]? payload = null) =>
        payload == null ? new[] { type } : Crypto.Prims.Cat(new[] { type }, payload);
}
