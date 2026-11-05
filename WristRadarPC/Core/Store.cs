using System.Security.Cryptography;
using System.Text.Json;
using WristRadarPC.Crypto;

namespace WristRadarPC.Core;

/// <summary>
/// Local persistence under %APPDATA%\WristRadarPC. Secrets (identity seed, pairing key, messages,
/// pictures) are protected with Windows DPAPI (CurrentUser scope) - the PC counterpart of the
/// watch's Android Keystore. Nothing is written in plaintext except non-secret preferences.
/// </summary>
public sealed class Store : ISigner
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WristRadarPC");
    static readonly byte[] Entropy = Prims.B("WristRadarPC-v1");
    readonly byte[] _seed;
    public byte[] PublicKey { get; }

    public Store()
    {
        Directory.CreateDirectory(Dir);
        Directory.CreateDirectory(Path.Combine(Dir, "images"));
        var seed = ReadProtected("identity.bin");
        if (seed == null || seed.Length != 32) { seed = Prims.Random(32); WriteProtected("identity.bin", seed); }
        _seed = seed;
        PublicKey = Prims.EdPublic(seed);
        Profile = ReadJson<ProfileData>("profile.json", protect: false) ?? new ProfileData { Name = "PC-" + Random.Shared.Next(1000, 9999) };
        Trust = ReadJson<TrustData>("trust.bin", protect: true);
        Messages = ReadJson<List<Msg>>("history.bin", protect: true) ?? new();
        SecurityLog = ReadJson<List<SecurityEvent>>("security.json", protect: false) ?? new();
    }

    public byte[] Sign(byte[] data) => Prims.EdSign(_seed, data);
    public string Fingerprint => Fp(PublicKey);
    public static string Fp(byte[] pub) => string.Join(" ", Enumerable.Range(0, 3).Select(i => Convert.ToHexString(Prims.Sha256(pub), i * 2, 2)));

    // ---------------- data ----------------
    public ProfileData Profile { get; }
    public TrustData? Trust { get; private set; }
    public List<Msg> Messages { get; }
    public List<SecurityEvent> SecurityLog { get; }

    public void SaveProfile() => WriteJson("profile.json", Profile, protect: false);
    public void SaveMessages() { lock (Messages) WriteJson("history.bin", Messages, protect: true); }
    public void SaveSecurityLog() { lock (SecurityLog) WriteJson("security.json", SecurityLog, protect: false); }

    public void SetTrust(TrustData t) { Trust = t; WriteJson("trust.bin", t, protect: true); }
    public void SaveTrust() { if (Trust != null) WriteJson("trust.bin", Trust, protect: true); }

    public long NextSeq() { if (Trust == null) return 0; Trust.Seq++; SaveTrust(); return Trust.Seq; }

    public void ClearPartner()
    {
        if (Trust != null) Prims.Wipe(Trust.Psk);
        Trust = null;
        TryDelete("trust.bin");
        lock (Messages) Messages.Clear();
        SaveMessages();
        foreach (var f in Directory.GetFiles(Path.Combine(Dir, "images"))) TryDelete(f);
    }

    public void SaveImage(string id, byte[] jpeg) => File.WriteAllBytes(ImgPath(id), Protect(jpeg));
    public byte[]? LoadImage(string id) { var p = ImgPath(id); return File.Exists(p) ? Unprotect(File.ReadAllBytes(p)) : null; }
    static string ImgPath(string id) => Path.Combine(Dir, "images", id + ".bin");

    // ---------------- helpers ----------------
    static byte[] Protect(byte[] d) => ProtectedData.Protect(d, Entropy, DataProtectionScope.CurrentUser);
    static byte[]? Unprotect(byte[] d) { try { return ProtectedData.Unprotect(d, Entropy, DataProtectionScope.CurrentUser); } catch { return null; } }

    static byte[]? ReadProtected(string name)
    {
        var p = Path.Combine(Dir, name);
        return File.Exists(p) ? Unprotect(File.ReadAllBytes(p)) : null;
    }

    static void WriteProtected(string name, byte[] data) => AtomicWrite(Path.Combine(Dir, name), Protect(data));

    static T? ReadJson<T>(string name, bool protect)
    {
        try
        {
            var p = Path.Combine(Dir, name);
            if (!File.Exists(p)) return default;
            var bytes = File.ReadAllBytes(p);
            if (protect) bytes = Unprotect(bytes) ?? throw new InvalidDataException();
            return JsonSerializer.Deserialize<T>(bytes);
        }
        catch { return default; }
    }

    static void WriteJson<T>(string name, T value, bool protect)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value);
        AtomicWrite(Path.Combine(Dir, name), protect ? Protect(bytes) : bytes);
    }

    static void AtomicWrite(string path, byte[] data)
    {
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, data);
        File.Move(tmp, path, overwrite: true);
    }

    static void TryDelete(string nameOrPath)
    {
        try { File.Delete(Path.IsPathRooted(nameOrPath) ? nameOrPath : Path.Combine(Dir, nameOrPath)); } catch { }
    }
}

public sealed class ProfileData
{
    public string Name { get; set; } = "PC";
    public byte[]? Avatar { get; set; }          // JPEG, 160 px
    public string Bio { get; set; } = "";
    public bool Sound { get; set; } = true;
    public bool Music { get; set; } = true;
}

public sealed class TrustData
{
    public byte[] PartnerId { get; set; } = Array.Empty<byte>();
    public byte[] Psk { get; set; } = Array.Empty<byte>();
    public long PairedAt { get; set; }
    public long LastConnected { get; set; }
    public string? PartnerName { get; set; }
    public byte[]? PartnerAvatar { get; set; }
    public string PartnerBio { get; set; } = "";
    public long PartnerLastSeen { get; set; }
    public long Seq { get; set; }
}

public static class MsgStatus { public const int Pending = 0, Delivered = 1, Received = 2, Transferring = 3, Failed = 4, Seen = 5; }

public sealed class Msg
{
    public string Id { get; set; } = "";
    public bool Outgoing { get; set; }
    public bool IsImage { get; set; }
    public bool IsAudio { get; set; }
    public int DurationMs { get; set; }
    public bool IsAlert { get; set; }
    public bool SeenSent { get; set; }
    public string Text { get; set; } = "";
    public long Seq { get; set; }
    public long SentAt { get; set; }
    public long LocalTime { get; set; }
    public int Status { get; set; }
    public int Progress { get; set; }
    public int Width { get; set; }
    public int Height { get; set; }
    public string? ReactMine { get; set; }
    public string? ReactTheirs { get; set; }
    public string? ReplyTo { get; set; }
    public bool Edited { get; set; }
    public bool Pinned { get; set; }
}

public sealed class SecurityEvent
{
    public long Time { get; set; }
    public string Type { get; set; } = "";
    public string Detail { get; set; } = "";
    public string Device { get; set; } = "";
}
