using WristRadarPC.Crypto;

namespace WristRadarPC;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        if (args.Contains("--selftest")) return SelfTest.Run(args.SkipWhile(a => a != "--selftest").Skip(1).FirstOrDefault());
        if (args.Contains("--snapshot"))
        {
            ApplicationConfiguration.Initialize();
            var f = new Ui.MainForm { StartPosition = FormStartPosition.Manual, Location = new Point(-3000, 0) };
            f.Shown += async (_, _) => { await Task.Delay(2500); using var bmp = new Bitmap(f.Width, f.Height); f.DrawToBitmap(bmp, new Rectangle(0, 0, f.Width, f.Height)); bmp.Save(args[^1]); f.Close(); };
            Application.Run(f);
            return 0;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new Ui.MainForm());
        return 0;
    }
}

/// <summary>Known-answer checks against vectors printed by the watch's Kotlin code (InteropVectorsTest).</summary>
static class SelfTest
{
    public static int Run(string? outFile)
    {
        var results = new List<string>();
        bool all = true;
        void Check(string name, string got, string want) { bool ok = got == want; all &= ok; results.Add($"{(ok ? "PASS" : "FAIL")} {name}{(ok ? "" : $"\n   got  {got}\n   want {want}")}"); }

        var seed = Enumerable.Range(1, 32).Select(i => (byte)i).ToArray();
        Check("ed25519 public", Prims.Hex(Prims.EdPublic(seed)), "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664");
        Check("ed25519 signature", Prims.Hex(Prims.EdSign(seed, Prims.B("hello"))), "6970dad564d940df9017a22431bc2d52fae0b56ce07b860fbe3819fe7128653ccb4ce6c05aef0141e84b1428cc6289fd6e1d5a0941e2005f4dfe534cdbb1990e");
        var a = Enumerable.Repeat((byte)0x11, 32).ToArray(); var b = Enumerable.Repeat((byte)0x22, 32).ToArray();
        Check("x25519 public", Prims.Hex(Prims.X25519Public(a)), "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13");
        Check("x25519 shared", Prims.Hex(Prims.X25519Agree(a, Prims.X25519Public(b))), "9e004098efc091d4ec2663b4e9f5cfd4d7064571690b4bea97ab146ab9f35056");
        Check("hkdf", Prims.Hex(Prims.Hkdf(Enumerable.Repeat((byte)0x0b, 22).ToArray(), Enumerable.Range(0, 13).Select(i => (byte)i).ToArray(), "wr psk v2")), "3b09ec8d0591b7631331a6744bdd447e0e95d831266e8437e3fbb5bbc738612b");
        Check("hkdf empty salt", Prims.Hex(Prims.Hkdf(Enumerable.Repeat((byte)0x33, 32).ToArray(), null, "wr rekey")), "08630367b2c88e87890347a5f5e9b634f2639bda115e13a7cc9cbd226b462188");
        Check("aes-gcm", Prims.Hex(Prims.GcmSeal(Enumerable.Repeat((byte)0x33, 32).ToArray(), Enumerable.Repeat((byte)0x44, 12).ToArray(), Prims.B("msg"), Prims.B("aad"))), "5de2a3e4bfe8e712aba6a69659ba851db72321");
        var key = Advert.AdvertKeyFor(Enumerable.Repeat((byte)0x55, 32).ToArray(), Enumerable.Repeat((byte)0x77, 32).ToArray());
        Check("advert token", Prims.Hex(Advert.Token(key, 123)), "fde6bbf84d091847");
        var kp = MlKem.Generate();
        var (ct, ss) = MlKem.Encapsulate(kp.PublicKey);
        Check("ml-kem sizes", $"{kp.PublicKey.Length}/{ct.Length}", $"{MlKem.PublicKeySize}/{MlKem.CiphertextSize}");
        Check("ml-kem roundtrip", Prims.Hex(MlKem.Decapsulate(kp, ct)), Prims.Hex(ss));
        Check("service uuid LE", Prims.Hex(Advert.ServiceUuidLe()), "170a9e8d2c5fe1a60b4d4e9c521a3f7b");

        var sine = Enumerable.Range(0, 32).Select(i => (short)(int)(8000 * Math.Sin(i * 0.7))).ToArray();
        Check("adpcm (voice codec)", Prims.Hex(Core.Adpcm.EncodeBlock(sine, 32, new Core.Adpcm.State())), "0000007077f7ff6f13c8bb283582cc9a5222b9");
        var text = string.Join("\n", results) + $"\n{(all ? "ALL PASS" : "FAILURES")}\n";
        File.WriteAllText(outFile ?? "selftest.txt", text);
        return all ? 0 : 1;
    }
}
