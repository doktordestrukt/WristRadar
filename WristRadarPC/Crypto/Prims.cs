using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Kems;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Math.EC.Rfc7748;
using Org.BouncyCastle.Math.EC.Rfc8032;
using BcRandom = Org.BouncyCastle.Security.SecureRandom;

namespace WristRadarPC.Crypto;

/// <summary>
/// Same primitives as the watch: X25519 / Ed25519 / ML-KEM-768 (BouncyCastle), HKDF-SHA256,
/// AES-256-GCM, SHA-256, HMAC-SHA256 (.NET). Output formats match the Kotlin code byte-for-byte
/// (GCM output = ciphertext || 16-byte tag, like Java's Cipher).
/// </summary>
public static class Prims
{
    static readonly BcRandom Bc = new();

    public static byte[] Random(int n) => RandomNumberGenerator.GetBytes(n);
    public static byte[] B(string s) => Encoding.UTF8.GetBytes(s);

    public static byte[] Sha256(params byte[][] parts)
    {
        using var h = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var p in parts) h.AppendData(p);
        return h.GetHashAndReset();
    }

    public static byte[] Cat(params byte[][] parts)
    {
        var r = new byte[parts.Sum(p => p.Length)];
        int o = 0;
        foreach (var p in parts) { Buffer.BlockCopy(p, 0, r, o, p.Length); o += p.Length; }
        return r;
    }

    public static byte[] Hkdf(byte[] ikm, byte[]? salt, string info, int len = 32) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, len, salt ?? Array.Empty<byte>(), B(info));

    public static byte[] Hmac(byte[] key, byte[] data) => HMACSHA256.HashData(key, data);

    public static byte[] GcmSeal(byte[] key, byte[] nonce, byte[] plain, byte[]? aad)
    {
        using var g = new AesGcm(key, 16);
        var ct = new byte[plain.Length];
        var tag = new byte[16];
        g.Encrypt(nonce, plain, ct, tag, aad);
        return Cat(ct, tag);
    }

    public static byte[]? GcmOpen(byte[] key, byte[] nonce, byte[] data, byte[]? aad)
    {
        if (data.Length < 16) return null;
        try
        {
            using var g = new AesGcm(key, 16);
            var pt = new byte[data.Length - 16];
            g.Decrypt(nonce, data.AsSpan(0, data.Length - 16), data.AsSpan(data.Length - 16), pt, aad);
            return pt;
        }
        catch (CryptographicException) { return null; }
    }

    public static bool CtEquals(byte[]? a, byte[]? b) =>
        a != null && b != null && CryptographicOperations.FixedTimeEquals(a, b);

    // ---- X25519 ----
    public static byte[] X25519Private() { var k = new byte[32]; X25519.GeneratePrivateKey(Bc, k); return k; }
    public static byte[] X25519Public(byte[] priv) { var p = new byte[32]; X25519.GeneratePublicKey(priv, 0, p, 0); return p; }
    /// <summary>Throws on low-order points (all-zero result), like Tink.</summary>
    public static byte[] X25519Agree(byte[] priv, byte[] pub)
    {
        if (pub.Length != 32) throw new CryptographicException("bad x25519 key");
        var r = new byte[32];
        if (!X25519.CalculateAgreement(priv, 0, pub, 0, r, 0)) throw new CryptographicException("low-order point");
        return r;
    }

    // ---- Ed25519 (32-byte seed, same as Tink) ----
    public static byte[] EdPublic(byte[] seed) { var p = new byte[32]; Ed25519.GeneratePublicKey(seed, 0, p, 0); return p; }
    public static byte[] EdSign(byte[] seed, byte[] msg) { var s = new byte[64]; Ed25519.Sign(seed, 0, msg, 0, msg.Length, s, 0); return s; }
    public static bool EdVerify(byte[] pub, byte[] sig, byte[] msg)
    {
        if (pub.Length != 32 || sig.Length != 64) return false;
        try { return Ed25519.Verify(sig, 0, pub, 0, msg, 0, msg.Length); } catch { return false; }
    }

    public static void Wipe(byte[]? b) { if (b != null) CryptographicOperations.ZeroMemory(b); }

    public static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    public static int CompareUnsigned(byte[] a, byte[] b)
    {
        for (int i = 0; i < Math.Min(a.Length, b.Length); i++) { int d = a[i] - b[i]; if (d != 0) return d; }
        return a.Length - b.Length;
    }
}

/// <summary>ML-KEM-768 via BouncyCastle, used only in hybrid with X25519.</summary>
public static class MlKem
{
    public const int PublicKeySize = 1184, CiphertextSize = 1088;
    static readonly BcRandom Rng = new();

    public sealed class KeyPair
    {
        public byte[] PublicKey { get; init; } = Array.Empty<byte>();
        internal MLKemPrivateKeyParameters Private { get; init; } = null!;
    }

    public static KeyPair Generate()
    {
        var g = new MLKemKeyPairGenerator();
        g.Init(new MLKemKeyGenerationParameters(Rng, MLKemParameters.ml_kem_768));
        var kp = g.GenerateKeyPair();
        return new KeyPair { PublicKey = ((MLKemPublicKeyParameters)kp.Public).GetEncoded(), Private = (MLKemPrivateKeyParameters)kp.Private };
    }

    public static (byte[] ct, byte[] secret) Encapsulate(byte[] publicKey)
    {
        if (publicKey.Length != PublicKeySize) throw new CryptographicException("bad ML-KEM key");
        var e = new MLKemEncapsulator(MLKemParameters.ml_kem_768);
        e.Init(new ParametersWithRandom(MLKemPublicKeyParameters.FromEncoding(MLKemParameters.ml_kem_768, publicKey), Rng));
        var ct = new byte[e.EncapsulationLength];
        var ss = new byte[e.SecretLength];
        e.Encapsulate(ct, 0, ct.Length, ss, 0, ss.Length);
        return (ct, ss);
    }

    public static byte[] Decapsulate(KeyPair kp, byte[] ct)
    {
        if (ct.Length != CiphertextSize) throw new CryptographicException("bad ML-KEM ciphertext");
        var d = new MLKemDecapsulator(MLKemParameters.ml_kem_768);
        d.Init(kp.Private);
        var ss = new byte[d.SecretLength];
        d.Decapsulate(ct, 0, ct.Length, ss, 0, ss.Length);
        return ss;
    }
}
