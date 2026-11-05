using System.Drawing.Drawing2D;
using WristRadarPC.Core;

namespace WristRadarPC.Ui;

/// <summary>Rounded, flat, glowing button.</summary>
public sealed class WrButton : Control
{
    public bool Accent { get; set; }
    public bool DangerStyle { get; set; }
    bool _hover, _down;

    public WrButton(string text)
    {
        Text = text;
        DoubleBuffered = true;
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        Height = 36; Cursor = Cursors.Hand; Font = Theme.Ui; ForeColor = Theme.Text; BackColor = Color.Transparent;
    }

    protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { _hover = false; _down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { _down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { _down = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = new RectangleF(1, 1, Width - 3, Height - 3);
        using var path = Theme.Round(r, Math.Min(14, r.Height / 2));
        Color baseC = DangerStyle ? Color.FromArgb(60, Theme.Danger) : Accent ? Theme.Secondary : Theme.Surface2;
        if (!Enabled) baseC = Color.FromArgb(40, 40, 46);
        else if (_down) baseC = ControlPaint.Dark(baseC, 0.1f);
        else if (_hover) baseC = ControlPaint.Light(baseC, 0.15f);
        using (var b = Accent && Enabled
                   ? (Brush)new LinearGradientBrush(Rectangle.Round(r), Theme.Primary, Theme.Secondary, 0f)
                   : new SolidBrush(baseC))
            g.FillPath(b, path);
        if (_hover && Enabled) { using var glow = new Pen(Color.FromArgb(160, DangerStyle ? Theme.Danger : Theme.Primary), 1.5f); g.DrawPath(glow, path); }
        var fg = !Enabled ? Theme.Dim : DangerStyle ? Theme.Danger : Theme.Text;
        TextRenderer.DrawText(g, Text, Font, Rectangle.Round(r), fg, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }
}

/// <summary>Animated radar sweep with blips for nearby watches.</summary>
public sealed class RadarView : Control
{
    float _angle;
    public List<(float ang, float rad)> Blips { get; set; } = new();
    readonly System.Windows.Forms.Timer _t = new() { Interval = 33 };

    public RadarView()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        BackColor = Theme.Bg;
        _t.Tick += (_, _) => { _angle = (_angle + 4) % 360; Invalidate(); };
        _t.Start();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float s = Math.Min(Width, Height) - 8, cx = Width / 2f, cy = Height / 2f, r = s / 2;
        for (int i = 1; i <= 3; i++) { using var p = new Pen(Color.FromArgb(50 + 25 * i, Theme.Primary), 1.2f); float rr = r * i / 3; g.DrawEllipse(p, cx - rr, cy - rr, rr * 2, rr * 2); }
        using (var p = new Pen(Color.FromArgb(40, Theme.Primary))) { g.DrawLine(p, cx - r, cy, cx + r, cy); g.DrawLine(p, cx, cy - r, cx, cy + r); }
        using (var path = new GraphicsPath())
        {
            path.AddPie(cx - r, cy - r, r * 2, r * 2, _angle - 60, 60);
            using var pb = new PathGradientBrush(path) { CenterColor = Color.FromArgb(120, Theme.Primary), SurroundColors = new[] { Color.FromArgb(0, Theme.Primary) } };
            g.FillPath(pb, path);
        }
        double rad = _angle * Math.PI / 180;
        using (var p = new Pen(Theme.Primary, 2)) g.DrawLine(p, cx, cy, cx + (float)Math.Cos(rad) * r, cy + (float)Math.Sin(rad) * r);
        foreach (var (a, d) in Blips)
        {
            float x = cx + (float)Math.Cos(a) * r * d, y = cy + (float)Math.Sin(a) * r * d;
            using var halo = new SolidBrush(Color.FromArgb(70, Theme.Primary)); g.FillEllipse(halo, x - 9, y - 9, 18, 18);
            using var dot = new SolidBrush(Theme.Text); g.FillEllipse(dot, x - 4, y - 4, 8, 8);
        }
        using (var c = new SolidBrush(Theme.Primary)) g.FillEllipse(c, cx - 4, cy - 4, 8, 8);
    }

    protected override void Dispose(bool disposing) { if (disposing) _t.Dispose(); base.Dispose(disposing); }
}

/// <summary>One chat bubble (text or picture).</summary>
public sealed class Bubble : Control
{
    public Msg M { get; private set; }
    readonly Func<string, Image?> _loadImg;
    Image? _img;
    const int Pad = 10, MaxW = 380;

    public Bubble(Msg m, Func<string, Image?> loadImg)
    {
        M = m; _loadImg = loadImg;
        DoubleBuffered = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        BackColor = Theme.Bg; Font = Theme.Ui; Margin = new Padding(0, 3, 0, 3);
        if (m.IsImage) Cursor = Cursors.Hand;
    }

    /// <summary>Reply-to quote is shown as a first line.</summary>
    public static Func<string, string?>? QuoteLookup;
    string DisplayText => M.ReplyTo == null ? M.Text : "↩ " + (QuoteLookup?.Invoke(M.ReplyTo) ?? "deleted message") + "\n" + M.Text;

    public void Update(Msg m) { M = m; if (m.IsImage && _img == null) _img = null; Relayout(); Invalidate(); }

    public void Relayout()
    {
        if (Parent == null) return;
        Width = Parent.ClientSize.Width - 28;
        Height = Measure().Height + 18;
    }

    Size Measure()
    {
        if (M.IsAudio) return new Size(220, 40);
        if (M.IsImage)
        {
            int w = 240; float ratio = M.Width > 0 && M.Height > 0 ? (float)M.Height / M.Width : 0.75f;
            return new Size(w, (int)Math.Clamp(w * ratio, 120, 320));
        }
        var s = TextRenderer.MeasureText(DisplayText, Font, new Size(MaxW, 4000), TextFormatFlags.WordBreak);
        return new Size(Math.Max(s.Width, 30), s.Height);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        if (M.IsAlert)
        {
            // Intercepted / tampered message that was blocked: red, centred.
            var ts = TextRenderer.MeasureText(M.Text, Theme.Ui);
            var ar = new RectangleF((Width - ts.Width) / 2f - 12, 2, ts.Width + 24, ts.Height + 12);
            using (var ap = Theme.Round(ar, 10)) using (var ab = new SolidBrush(Color.FromArgb(220, Theme.Danger))) g.FillPath(ab, ap);
            TextRenderer.DrawText(g, M.Text, Theme.Ui, Rectangle.Round(ar), Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            return;
        }
        var content = Measure();
        int bw = content.Width + Pad * 2, bh = content.Height + Pad * 2 - (M.IsImage ? 14 : 0);
        int x = M.Outgoing ? Width - bw - 4 : 4;
        var rect = new RectangleF(x, 0, bw, Math.Max(bh, 24));
        using (var path = Theme.Round(rect, 14))
        using (var b = new SolidBrush(M.Outgoing ? Theme.Outgoing : Theme.Surface2))
            g.FillPath(b, path);
        if (M.IsAudio)
        {
            bool playing = VoicePlayback.PlayingId == M.Id;
            using (var c = new SolidBrush(Theme.Primary)) g.FillEllipse(c, x + 10, 8, 34, 34);
            TextRenderer.DrawText(g, playing ? "❚❚" : "▶", Theme.MonoBold, new Rectangle(x + 10, 8, 34, 34), Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            bool ready = M.Outgoing || M.Status == MsgStatus.Received;
            var label = ready ? $"🎤 Voice message · {M.DurationMs / 1000 / 60}:{M.DurationMs / 1000 % 60:00}" : $"🎤 receiving… {M.Progress}%";
            TextRenderer.DrawText(g, label, Font, new Point(x + 52, 16), Theme.Text);
        }
        else if (M.IsImage)
        {
            _img ??= (M.Outgoing || M.Status == MsgStatus.Received) ? _loadImg(M.Id) : null;
            var ir = new Rectangle(x + 4, 4, bw - 8, (int)rect.Height - 8);
            if (_img != null)
            {
                using var clip = Theme.Round(ir, 10);
                var st = g.Save(); g.SetClip(clip); g.DrawImage(_img, ir); g.Restore(st);
            }
            if (M.Status is MsgStatus.Transferring or MsgStatus.Pending && M.Progress < 100)
            {
                using var dim = new SolidBrush(Color.FromArgb(110, 0, 0, 0)); g.FillRectangle(dim, ir);
                TextRenderer.DrawText(g, $"{M.Progress}%", Theme.MonoBold, ir, Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            }
        }
        else
        {
            TextRenderer.DrawText(g, DisplayText, Font, new Rectangle(x + Pad, Pad, content.Width + 2, content.Height + 4), Theme.Text, TextFormatFlags.WordBreak);
        }
        var time = (M.Pinned ? "📌 " : "") + DateTimeOffset.FromUnixTimeMilliseconds(M.LocalTime).ToLocalTime().ToString("HH:mm") + (M.Edited ? " · edited" : "");
        var reacts = string.Join(" ", new[] { M.ReactTheirs, M.ReactMine }.Where(x => !string.IsNullOrEmpty(x)));
        if (reacts.Length > 0) time = reacts + "  " + time;
        string status = !M.Outgoing ? "" : M.Status switch
        {
            MsgStatus.Pending => " · sending",
            MsgStatus.Transferring => $" · {M.Progress}%",
            MsgStatus.Delivered => " · ✓✓",
            MsgStatus.Seen => " · ✓✓",
            MsgStatus.Failed => " · failed",
            _ => "",
        };
        var meta = time + status;
        var ms = TextRenderer.MeasureText(meta, Theme.UiSmall);
        int mx = M.Outgoing ? Width - ms.Width - 8 : 8;
        TextRenderer.DrawText(g, meta, Theme.UiSmall, new Point(mx, (int)rect.Bottom + 1), M.Status == MsgStatus.Failed ? Theme.Danger : M.Status == MsgStatus.Seen ? Color.FromArgb(0x34, 0xA6, 0xFF) : Theme.Dim);
    }
}
