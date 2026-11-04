using System.Drawing.Drawing2D;
using System.Reflection;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace WristRadarPC.Ui;

/// <summary>Midnight Purple, matching the watch's default theme.</summary>
public static class Theme
{
    public static readonly Color Bg = Color.FromArgb(0x0B, 0x09, 0x10);
    public static readonly Color Surface = Color.FromArgb(0x16, 0x12, 0x1C);
    public static readonly Color Surface2 = Color.FromArgb(0x1E, 0x1A, 0x26);
    public static readonly Color Primary = Color.FromArgb(0xB2, 0x66, 0xFF);
    public static readonly Color Secondary = Color.FromArgb(0x7A, 0x3D, 0xCC);
    public static readonly Color Outgoing = Color.FromArgb(0x4A, 0x23, 0x80);
    public static readonly Color Text = Color.FromArgb(0xED, 0xE7, 0xF6);
    public static readonly Color Dim = Color.FromArgb(0x9A, 0x92, 0xA8);
    public static readonly Color Good = Color.FromArgb(0x39, 0xFF, 0x88);
    public static readonly Color Danger = Color.FromArgb(0xFF, 0x53, 0x70);
    public static readonly Font Mono = new("Consolas", 10f);
    public static readonly Font MonoBold = new("Consolas", 10f, FontStyle.Bold);
    public static readonly Font Title = new("Consolas", 18f, FontStyle.Bold);
    public static readonly Font Ui = new("Segoe UI", 10f);
    public static readonly Font UiSmall = new("Segoe UI", 8.5f);
    public static readonly Font Code = new("Consolas", 34f, FontStyle.Bold);

    public static Color PhaseColor(string phase) => phase switch
    {
        "CONNECTED" => Good,
        "CONNECTION FAILED" or "BLUETOOTH ERROR" => Danger,
        "NOT PAIRED" or "STANDBY" => Dim,
        _ => Primary,
    };

    public static GraphicsPath Round(RectangleF r, float rad)
    {
        var p = new GraphicsPath();
        float d = rad * 2;
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    public static Image? FromJpeg(byte[]? b)
    {
        if (b == null) return null;
        try { using var ms = new MemoryStream(b); return Image.FromStream(ms, true, true); } catch { return null; }
    }

    public static void DrawAvatar(Graphics g, Rectangle r, Image? img, string? name)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using var clip = new GraphicsPath();
        clip.AddEllipse(r);
        if (img != null)
        {
            var state = g.Save();
            g.SetClip(clip);
            int edge = Math.Min(img.Width, img.Height);
            g.DrawImage(img, r, new Rectangle((img.Width - edge) / 2, (img.Height - edge) / 2, edge, edge), GraphicsUnit.Pixel);
            g.Restore(state);
        }
        else
        {
            using var br = new LinearGradientBrush(r, Secondary, Color.FromArgb(150, Primary), 45f);
            g.FillEllipse(br, r);
            var initials = string.Concat((name ?? "?").Split(' ', '-', '_').Where(s => s.Length > 0).Take(2).Select(s => char.ToUpperInvariant(s[0])));
            using var f = new Font("Consolas", r.Height * 0.3f, FontStyle.Bold, GraphicsUnit.Pixel);
            TextRenderer.DrawText(g, initials.Length == 0 ? "?" : initials, f, r, Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
        using var pen = new Pen(Primary, 2f);
        g.DrawEllipse(pen, r);
    }

    public static string Distance(double? rssi)
    {
        if (rssi == null) return "--";
        double m = Math.Clamp(Math.Pow(10, (-62.0 - rssi.Value) / (10 * 2.3)), 0.3, 60);
        double ft = m * 3.28084;
        return m < 1 ? "< 1 m (< 3 ft)" : m < 5 ? $"≈ {Math.Round(m * 2) / 2:0.#} m (≈ {Math.Round(ft / 2) * 2:0} ft)" : $"≈ {Math.Round(m):0} m (≈ {Math.Round(ft / 5) * 5:0} ft)";
    }

    public static string Signal(double? rssi) => rssi switch { null => "No signal", >= -60 => "Excellent", >= -70 => "Good", >= -80 => "Fair", _ => "Weak" };
}

/// <summary>Plays the same bundled MP3s as the watch app (extracted once to %TEMP%).</summary>
public static class Sound
{
    static readonly string Dir = Path.Combine(Path.GetTempPath(), "WristRadarPC-audio");
    static MediaPlayer? _music;
    static readonly List<MediaPlayer> _oneShots = new();

    static Uri Extract(string name)
    {
        Directory.CreateDirectory(Dir);
        var path = Path.Combine(Dir, name);
        if (!File.Exists(path))
        {
            using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)!;
            using var f = File.Create(path);
            s.CopyTo(f);
        }
        return new Uri(path);
    }

    public static void Play(string name, Action? onEnd = null)
    {
        try
        {
            var p = new MediaPlayer { Source = MediaSource.CreateFromUri(Extract(name)) };
            lock (_oneShots) _oneShots.Add(p);
            p.MediaEnded += (s, _) => { lock (_oneShots) _oneShots.Remove(s); s.Dispose(); onEnd?.Invoke(); };
            p.MediaFailed += (s, _) => { lock (_oneShots) _oneShots.Remove(s); onEnd?.Invoke(); };
            p.Play();
        }
        catch { onEnd?.Invoke(); }
    }

    public static void StartMusic()
    {
        try
        {
            StopMusic();
            _music = new MediaPlayer { Source = MediaSource.CreateFromUri(Extract("hacker_music.mp3")), IsLoopingEnabled = true, Volume = 0.8 };
            _music.Play();
        }
        catch { }
    }

    public static void StopMusic() { try { _music?.Pause(); _music?.Dispose(); } catch { } _music = null; }
}
