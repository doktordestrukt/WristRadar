using System.Drawing.Drawing2D;
using WristRadarPC.Core;

namespace WristRadarPC.Ui;

/// <summary>
/// PC version of the watch's BLACKBOX screen: the same original laughing skull, CRT glitches,
/// typewriter event log, and the same audio (Glitch2 -> looping hacker music -> Glitch3 on dismiss).
/// </summary>
public sealed class BlackboxOverlay : Control
{
    static readonly Color Blood = Color.FromArgb(0xFF, 0x2A, 0x2A), Term = Color.FromArgb(0x39, 0xFF, 0x14),
        Bone = Color.FromArgb(0xE9, 0xE3, 0xD2), Cyan = Color.FromArgb(0x00, 0xF0, 0xFF);
    readonly System.Windows.Forms.Timer _t = new() { Interval = 50 };
    readonly WrButton _dismiss = new("DISMISS") { DangerStyle = true, Width = 200 };
    Alert? _a; DateTime _start; int _typed; float _jaw; bool _jawUp; readonly Random _rnd = new();
    bool _sound;
    public event Action? Dismissed;

    public BlackboxOverlay()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        BackColor = Color.Black; Visible = false; Dock = DockStyle.Fill;
        Controls.Add(_dismiss);
        _dismiss.Click += (_, _) => Close();
        _t.Tick += (_, _) => Step();
    }

    public void Show(Alert a, bool sound, bool music)
    {
        _a = a; _start = DateTime.Now; _typed = 0; _sound = sound;
        Visible = true; BringToFront(); Focus();
        _t.Start();
        if (sound) Sound.Play("glitch2.mp3", () => { if (music && Visible) BeginInvoke(Sound.StartMusic); });
        else if (music) Sound.StartMusic();
    }

    void Close()
    {
        _t.Stop(); Visible = false;
        Sound.StopMusic();
        if (_sound) Sound.Play("glitch3.mp3");
        Dismissed?.Invoke();
    }

    protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Escape) Close(); base.OnKeyDown(e); }

    protected override void OnLayout(LayoutEventArgs e)
    {
        _dismiss.Location = new Point((Width - _dismiss.Width) / 2, Height - 70);
        base.OnLayout(e);
    }

    void Step()
    {
        _jaw += _jawUp ? -0.3f : 0.3f;
        if (_jaw >= 1) { _jaw = 1; _jawUp = true; } else if (_jaw <= 0) { _jaw = 0; _jawUp = false; }
        if ((DateTime.Now - _start).TotalMilliseconds > 900) _typed += 3;
        Invalidate();
    }

    float Glitch()
    {
        var ms = (DateTime.Now - _start).TotalMilliseconds;
        if (ms < 1500) return 1f;
        return (ms / 120) % 28 < 2 ? 0.7f : 0f;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        if (_a == null) return;
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float gl = Glitch();
        int jitter = gl > 0 ? (int)((_rnd.NextDouble() - 0.5) * 18 * gl) : 0;
        g.TranslateTransform(jitter, 0);

        float s = Math.Min(260, Height * 0.38f), cx = Width / 2f, top = 30;
        using (var glow = new GraphicsPath())
        {
            glow.AddEllipse(cx - s * 0.62f, top - s * 0.08f, s * 1.24f, s * 1.24f);
            using var pb = new PathGradientBrush(glow) { CenterColor = Color.FromArgb(90, Blood), SurroundColors = new[] { Color.FromArgb(0, Blood) } };
            g.FillPath(pb, glow);
        }
        var st = g.Save();
        g.TranslateTransform(cx, top + s * 0.75f);
        g.RotateTransform((float)Math.Sin(_jaw * Math.PI) * 6f);
        g.TranslateTransform(-s / 2, -s * 0.75f);
        DrawSkull(g, s, _jaw, 0.6f + (float)_rnd.NextDouble() * 0.4f);
        g.Restore(st);

        float y = top + s + 6;
        string header = _a.Preview ? "BLACKBOX // PREVIEW · NO REAL EVENT" : _a.DeniedHere ? "BLACKBOX // ACCESS DENIED" : "BLACKBOX // UNAUTHORISED ACCESS DETECTED";
        y = GlitchText(g, "PLEASE DO NOT DECRYPT THIS", new Font("Consolas", 26, FontStyle.Bold), Blood, y, gl) + 4;
        y = GlitchText(g, header, new Font("Consolas", 13, FontStyle.Bold), Term, y, gl * 0.6f) + 14;

        var lines = new List<string> { "> EVENT: " + _a.Title, "> DETAIL: " + _a.Detail, "> DEVICE: " + _a.Device, "> TIME: " + _a.Time.ToString("HH:mm:ss") };
        if (_a.DeniedHere) lines.Add("> The watch logged this attempt.");
        else if (!_a.Preview) lines.Add("> Logged locally. No message content was exposed.");
        var full = string.Join("\n", lines);
        var shown = full[..Math.Min(_typed, full.Length)] + (_typed < full.Length ? "█" : "");
        using (var f = new Font("Consolas", 11))
            TextRenderer.DrawText(g, shown, f, new Rectangle((int)(cx - 330), (int)y, 660, 200), Term, TextFormatFlags.WordBreak);

        g.ResetTransform();
        using (var scan = new Pen(Color.FromArgb(70, 0, 0, 0)))
            for (int yy = 0; yy < Height; yy += 3) g.DrawLine(scan, 0, yy, Width, yy);
        var sweepY = (float)((DateTime.Now - _start).TotalMilliseconds % 2400 / 2400 * Height);
        using (var sb = new LinearGradientBrush(new RectangleF(0, sweepY - 30, Width, 60), Color.FromArgb(0, Term), Color.FromArgb(0, Term), 90f))
        {
            sb.InterpolationColors = new ColorBlend { Colors = new[] { Color.FromArgb(0, Term), Color.FromArgb(28, Term), Color.FromArgb(0, Term) }, Positions = new[] { 0f, 0.5f, 1f } };
            g.FillRectangle(sb, 0, sweepY - 30, Width, 60);
        }
        if (gl > 0)
            for (int i = 0; i < 6 * gl + 1; i++)
            {
                using var nb = new SolidBrush(Color.FromArgb((int)(46 * gl), _rnd.Next(2) == 0 ? Blood : Cyan));
                g.FillRectangle(nb, (float)_rnd.NextDouble() * Width * 0.3f, (float)_rnd.NextDouble() * Height, Width * (0.4f + (float)_rnd.NextDouble() * 0.6f), 2 + (float)_rnd.NextDouble() * 18);
            }
    }

    float GlitchText(Graphics g, string text, Font f, Color c, float y, float gl)
    {
        var size = TextRenderer.MeasureText(text, f);
        int x = (Width - size.Width) / 2;
        if (gl > 0)
        {
            int dx = (int)((_rnd.NextDouble() * 4 + 2) * gl);
            TextRenderer.DrawText(g, text, f, new Point(x - dx, (int)y), Color.FromArgb(170, Cyan));
            TextRenderer.DrawText(g, text, f, new Point(x + dx, (int)y), Color.FromArgb(170, Blood));
        }
        var shown = gl >= 0.5f ? new string(text.Select(ch => ch != ' ' && _rnd.NextDouble() < 0.12 * gl ? "#%&@$*!?/<>01"[_rnd.Next(13)] : ch).ToArray()) : text;
        TextRenderer.DrawText(g, shown, f, new Point(x, (int)y), c);
        f.Dispose();
        return y + size.Height;
    }

    static void DrawSkull(Graphics g, float s, float jaw, float eye)
    {
        float w = s, h = s, cx = w / 2;
        using var bone = new SolidBrush(Bone);
        using var black = new SolidBrush(Color.Black);
        using var crack = new Pen(Color.FromArgb(0x8C, 0x85, 0x77), 2);
        using var tooth = new Pen(Color.FromArgb(0x3A, 0x34, 0x2A), 1.5f);
        g.FillEllipse(bone, w * 0.14f, h * 0.04f, w * 0.72f, h * 0.62f);
        using (var p = Theme.Round(new RectangleF(w * 0.25f, h * 0.48f, w * 0.5f, h * 0.2f), w * 0.08f)) g.FillPath(bone, p);
        g.DrawLine(crack, cx + w * 0.05f, h * 0.06f, cx + w * 0.11f, h * 0.18f);
        g.DrawLine(crack, cx + w * 0.11f, h * 0.18f, cx + w * 0.07f, h * 0.26f);
        foreach (var ex in new[] { cx - w * 0.2f, cx + w * 0.2f })
        {
            g.FillEllipse(black, ex - w * 0.1f, h * 0.30f, w * 0.2f, h * 0.2f);
            using var halo = new SolidBrush(Color.FromArgb((int)(90 * eye), Blood)); g.FillEllipse(halo, ex - w * 0.075f, h * 0.40f - w * 0.075f, w * 0.15f, w * 0.15f);
            using var pupil = new SolidBrush(Color.FromArgb((int)(255 * eye), Blood)); g.FillEllipse(pupil, ex - w * 0.035f, h * 0.40f - w * 0.035f, w * 0.07f, w * 0.07f);
        }
        g.FillPolygon(black, new[] { new PointF(cx, h * 0.48f), new PointF(cx - w * 0.055f, h * 0.58f), new PointF(cx + w * 0.055f, h * 0.58f) });
        float teethTop = h * 0.62f;
        g.FillRectangle(bone, w * 0.3f, teethTop, w * 0.4f, h * 0.07f);
        for (int i = 0; i <= 6; i++) { float x = w * 0.3f + i * w * 0.4f / 7; g.DrawLine(tooth, x, teethTop, x, teethTop + h * 0.07f); }
        float drop = h * 0.13f * jaw;
        g.FillRectangle(black, w * 0.3f, teethTop + h * 0.07f, w * 0.4f, drop);
        using (var p = Theme.Round(new RectangleF(w * 0.28f, teethTop + h * 0.07f + drop, w * 0.44f, h * 0.16f), w * 0.1f)) g.FillPath(bone, p);
        for (int i = 0; i <= 6; i++) { float x = w * 0.3f + i * w * 0.4f / 7; g.DrawLine(tooth, x, teethTop + h * 0.07f + drop, x, teethTop + h * 0.13f + drop); }
    }

    protected override void Dispose(bool disposing) { if (disposing) _t.Dispose(); base.Dispose(disposing); }
}
