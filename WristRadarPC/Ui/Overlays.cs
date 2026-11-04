using System.Drawing.Drawing2D;
using WristRadarPC.Core;

namespace WristRadarPC.Ui;

public static class Rel
{
    public static string Ago(long thenMs)
    {
        if (thenMs <= 0) return "never";
        long s = Math.Max(0, (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - thenMs) / 1000);
        string P(long n, string u) => $"{n} {u}{(n == 1 ? "" : "s")} ago";
        return s < 10 ? "just now" : s < 60 ? P(s, "second") : s < 3600 ? P(s / 60, "minute") : s < 86400 ? P(s / 3600, "hour")
            : s < 2_592_000 ? P(s / 86400, "day") : s < 31_536_000 ? P(s / 2_592_000, "month") : P(s / 31_536_000, "year");
    }
}

/// <summary>"slide to answer / hang up" track - drag the knob past 80 %.</summary>
public sealed class SlideBar : Control
{
    public Color Accent { get; set; } = Theme.Good;
    public event Action? Completed;
    float _x; bool _drag; float _grab;
    readonly System.Windows.Forms.Timer _spring = new() { Interval = 15 };

    public SlideBar(string text)
    {
        Text = text; Height = 54; Width = 360; DoubleBuffered = true; Cursor = Cursors.Hand; Font = new Font("Segoe UI", 11f);
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        _spring.Tick += (_, _) => { _x *= 0.75f; if (_x < 1) { _x = 0; _spring.Stop(); } Invalidate(); };
    }

    float Max => Width - Height;
    public void Reset() { _x = 0; Invalidate(); }

    protected override void OnMouseDown(MouseEventArgs e) { if (e.X >= _x && e.X <= _x + Height) { _drag = true; _grab = e.X - _x; _spring.Stop(); } }
    protected override void OnMouseMove(MouseEventArgs e) { if (_drag) { _x = Math.Clamp(e.X - _grab, 0, Max); Invalidate(); } }
    protected override void OnMouseUp(MouseEventArgs e)
    {
        if (!_drag) return;
        _drag = false;
        if (_x > Max * 0.8f) { _x = Max; Invalidate(); Completed?.Invoke(); }
        else _spring.Start();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = new RectangleF(0, 0, Width - 1, Height - 1);
        using (var path = Theme.Round(r, Height / 2f))
        using (var b = new LinearGradientBrush(Rectangle.Round(r), Color.FromArgb(50, Accent), Color.FromArgb(110, Accent), 0f))
        { g.FillPath(b, path); using var pen = new Pen(Color.FromArgb(160, Accent)); g.DrawPath(pen, path); }
        int alpha = (int)(255 * Math.Clamp(1 - _x / Math.Max(1, Max), 0, 1));
        TextRenderer.DrawText(g, Text, Font, new Rectangle(Height, 0, Width - Height, Height), Color.FromArgb(alpha, Theme.Text), TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        using (var k = new SolidBrush(Accent)) g.FillEllipse(k, _x + 4, 4, Height - 8, Height - 8);
        TextRenderer.DrawText(g, "➜", new Font("Segoe UI", 14f, FontStyle.Bold), new Rectangle((int)_x, 0, Height, Height), Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
    }
}

/// <summary>iPhone-style call screen matched to the WristRadar theme.</summary>
public sealed class CallOverlay : Control
{
    readonly Engine E;
    readonly SlideBar _answer = new("slide to answer") { Accent = Theme.Good };
    readonly SlideBar _hang = new("slide to hang up") { Accent = Theme.Danger };
    readonly WrButton _decline = new("Decline") { DangerStyle = true, Width = 160 };
    readonly WrButton _mute = new("Mute") { Width = 160 };
    readonly WrButton _voicemail = new("Leave a voicemail") { Accent = true, Width = 220 };
    readonly WrButton _close = new("Close") { Width = 160 };
    readonly System.Windows.Forms.Timer _t = new() { Interval = 40 };
    float _ring;
    public event Action? VoicemailRequested;

    public CallOverlay(Engine e)
    {
        E = e; Dock = DockStyle.Fill; Visible = false; DoubleBuffered = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        Controls.AddRange(new Control[] { _answer, _hang, _decline, _mute, _voicemail, _close });
        _answer.Completed += () => E.AcceptCall();
        _hang.Completed += () => E.HangUp();
        _decline.Click += (_, _) => E.DeclineCall();
        _mute.Click += (_, _) => { E.Muted = !E.Muted; _mute.Text = E.Muted ? "Unmute" : "Mute"; _mute.Invalidate(); };
        _voicemail.Click += (_, _) => { E.ResetCall(); VoicemailRequested?.Invoke(); };
        _close.Click += (_, _) => E.ResetCall();
        _t.Tick += (_, _) => { _ring = (_ring + 0.02f) % 1f; Invalidate(); };
        E.CallChanged += Sync;
    }

    void Sync()
    {
        var c = E.Call;
        Visible = c != CallPhase.Idle;
        if (!Visible) { _t.Stop(); return; }
        BringToFront(); _t.Start();
        _answer.Reset(); _hang.Reset();
        _answer.Visible = _decline.Visible = c == CallPhase.Incoming;
        _hang.Visible = c is CallPhase.Outgoing or CallPhase.Active;
        _hang.Text = c == CallPhase.Outgoing ? "slide to cancel" : "slide to hang up";
        _mute.Visible = c == CallPhase.Active; _mute.Text = "Mute";
        _voicemail.Visible = c == CallPhase.NoAnswer;
        _close.Visible = c == CallPhase.NoAnswer;
        if (c == CallPhase.Ended) _ = Task.Delay(1800).ContinueWith(_ => BeginInvoke(() => { if (E.Call == CallPhase.Ended) E.ResetCall(); }));
        PerformLayout();
    }

    protected override void OnLayout(LayoutEventArgs e)
    {
        int cx = Width / 2, y = Height - 150;
        foreach (var s in new Control[] { _answer, _hang }) s.Location = new Point(cx - s.Width / 2, y);
        _voicemail.Location = new Point(cx - _voicemail.Width / 2, y);
        _decline.Location = new Point(cx - _decline.Width / 2, y + 66);
        _close.Location = new Point(cx - _close.Width / 2, y + 50);
        _mute.Location = new Point(cx - _mute.Width / 2, y - 56);
        base.OnLayout(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var bg = new LinearGradientBrush(ClientRectangle, Theme.Bg, Color.FromArgb(60, Theme.Secondary), 90f)) g.FillRectangle(bg, ClientRectangle);
        int cx = Width / 2, cy = Height / 2 - 110, r = 70;
        if (E.Call is CallPhase.Incoming or CallPhase.Outgoing)
            for (int k = 0; k < 2; k++)
            {
                float f = (_ring + k * 0.5f) % 1f, rr = r * (1.05f + 0.6f * f);
                using var p = new Pen(Color.FromArgb((int)(160 * (1 - f)), Theme.Primary), 2.5f);
                g.DrawEllipse(p, cx - rr, cy - rr, rr * 2, rr * 2);
            }
        using var img = Theme.FromJpeg(E.S.Trust?.PartnerAvatar);
        Theme.DrawAvatar(g, new Rectangle(cx - r, cy - r, r * 2, r * 2), img, E.S.Trust?.PartnerName);
        var name = E.S.Trust?.PartnerName ?? "Partner";
        using var nf = new Font("Segoe UI", 22f, FontStyle.Bold);
        TextRenderer.DrawText(g, name, nf, new Rectangle(0, cy + r + 14, Width, 44), Theme.Text, TextFormatFlags.HorizontalCenter);
        string status = E.Call switch
        {
            CallPhase.Incoming => "WristRadar call…",
            CallPhase.Outgoing => "Calling…",
            CallPhase.Active => (E.Muted ? "Muted · " : "") + (DateTime.Now - E.CallSince).ToString(@"m\:ss"),
            CallPhase.NoAnswer => "No answer",
            CallPhase.Ended => E.CallEndReason,
            _ => "",
        };
        TextRenderer.DrawText(g, status, new Font("Consolas", 12f), new Rectangle(0, cy + r + 58, Width, 26),
            E.Call == CallPhase.Active ? Theme.Good : Theme.Dim, TextFormatFlags.HorizontalCenter);
    }
}

/// <summary>Themed warning for the legitimate users, with instructions.</summary>
public sealed class WarningOverlay : Control
{
    readonly WrButton _ok = new("I understand") { Accent = true, Width = 220 };
    Warning? _w;
    public event Action? Dismissed;

    public WarningOverlay()
    {
        Dock = DockStyle.Fill; Visible = false; DoubleBuffered = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        Controls.Add(_ok);
        _ok.Click += (_, _) => { Visible = false; Dismissed?.Invoke(); };
    }

    public void Show(Warning w) { _w = w; Visible = true; BringToFront(); Invalidate(); }
    protected override void OnLayout(LayoutEventArgs e) { _ok.Location = new Point((Width - _ok.Width) / 2, Height - 80); base.OnLayout(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        if (_w == null) return;
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Theme.Bg);
        int y = 40;
        void Line(string t, Font f, Color c, int h) { TextRenderer.DrawText(g, t, f, new Rectangle(60, y, Width - 120, h), c, TextFormatFlags.HorizontalCenter | TextFormatFlags.WordBreak); y += h; }
        Line("⚠", new Font("Segoe UI", 40f), Theme.Danger, 76);
        Line("SECURITY WARNING", new Font("Consolas", 20f, FontStyle.Bold), Theme.Danger, 40);
        Line(_w.FromPartner ? "Your partner's watch blocked someone trying to break into your private link."
            : "Someone tried to break into your private link. They were blocked.", new Font("Segoe UI", 13f, FontStyle.Bold), Theme.Text, 52);
        Line($"What happened: {_w.Title}" + (_w.FromPartner ? "" : $" — {_w.Detail}"), Theme.Ui, Theme.Dim, 44);
        if (!_w.FromPartner) Line($"Device: {_w.Device}", Theme.Ui, Theme.Dim, 24);
        Line($"When: {_w.Time:HH:mm:ss}" + (_w.Repeats > 0 ? $" · {_w.Repeats + 1} attempts" : ""), Theme.Ui, Theme.Dim, 24);
        Line("Your messages are safe — nothing was decrypted.", Theme.Ui, Theme.Good, 34);
        Line("WHAT TO DO", Theme.MonoBold, Theme.Primary, 26);
        Line("1. Don't send anything sensitive for now.\n2. Check your partner's name and fingerprint.\n3. If it happens again, move elsewhere or turn Bluetooth off for a minute.\n4. Still worried? Unpair and pair again face to face, comparing the code carefully.",
            Theme.Ui, Theme.Text, 110);
    }
}

/// <summary>iPhone-style banner that drops in at the top of the window.</summary>
public sealed class TopBanner : Control
{
    readonly System.Windows.Forms.Timer _anim = new() { Interval = 15 };
    string _title = "", _text = ""; Image? _avatar;
    int _targetY, _hideAt; DateTime _shownAt;
    public event Action? Clicked;

    public TopBanner()
    {
        Size = new Size(420, 70); Visible = false; DoubleBuffered = true; Cursor = Cursors.Hand;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        _anim.Tick += (_, _) => Step();
        Click += (_, _) => { Hide(); Clicked?.Invoke(); };
    }

    public void Show(string title, string text, Image? avatar)
    {
        _title = title; _text = text; _avatar?.Dispose(); _avatar = avatar;
        Left = (Parent!.ClientSize.Width - Width) / 2; Top = -Height; _targetY = 12; _hideAt = 0;
        _shownAt = DateTime.Now; Visible = true; BringToFront(); _anim.Start(); Invalidate();
    }

    new void Hide() { _hideAt = 1; }

    void Step()
    {
        if (_hideAt == 0 && DateTime.Now - _shownAt > TimeSpan.FromSeconds(4)) _hideAt = 1;
        int target = _hideAt == 1 ? -Height - 4 : _targetY;
        Top += (int)Math.Round((target - Top) * 0.25) + Math.Sign(target - Top);
        if (_hideAt == 1 && Top <= -Height) { Visible = false; _anim.Stop(); }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = new RectangleF(1, 1, Width - 3, Height - 3);
        using (var p = Theme.Round(r, 22)) { using var b = new SolidBrush(Color.FromArgb(245, Theme.Surface2)); g.FillPath(b, p); using var pen = new Pen(Color.FromArgb(140, Theme.Primary)); g.DrawPath(pen, p); }
        Theme.DrawAvatar(g, new Rectangle(12, 12, 44, 44), _avatar, _title);
        TextRenderer.DrawText(g, _title, new Font("Segoe UI", 11f, FontStyle.Bold), new Rectangle(66, 12, Width - 80, 22), Theme.Text, TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(g, _text, Theme.Ui, new Rectangle(66, 34, Width - 80, 22), Theme.Dim, TextFormatFlags.EndEllipsis);
    }
}

/// <summary>Record a voice message / voicemail (max 30 s).</summary>
public sealed class VoiceRecordDialog : Form
{
    readonly Mic _mic = new();
    readonly List<short> _pcm = new();
    readonly Label _time = new() { Dock = DockStyle.Top, Height = 60, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Consolas", 22f, FontStyle.Bold), ForeColor = Theme.Text };
    readonly System.Windows.Forms.Timer _t = new() { Interval = 100 };
    public byte[]? Result { get; private set; }

    public VoiceRecordDialog(string title)
    {
        Text = title; Size = new Size(380, 230); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterParent; BackColor = Theme.Surface;
        var lbl = new Label { Text = "● Recording…", Dock = DockStyle.Top, Height = 36, ForeColor = Theme.Danger, TextAlign = ContentAlignment.MiddleCenter, Font = Theme.MonoBold };
        var row = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 56, Padding = new Padding(40, 8, 0, 0), BackColor = Theme.Surface };
        var cancel = new WrButton("Cancel") { Width = 130 };
        var send = new WrButton("Send") { Accent = true, Width = 130 };
        row.Controls.Add(cancel); row.Controls.Add(send);
        Controls.Add(_time); Controls.Add(lbl); Controls.Add(row);
        _mic.Frame += f => { lock (_pcm) if (_pcm.Count < Adpcm.SampleRate * 30) _pcm.AddRange(f); };
        _t.Tick += (_, _) =>
        {
            int n; lock (_pcm) n = _pcm.Count;
            _time.Text = $"{n / Adpcm.SampleRate / 60}:{n / Adpcm.SampleRate % 60:00} / 0:30";
            if (n >= Adpcm.SampleRate * 30) Finish();
        };
        cancel.Click += (_, _) => { Result = null; Close(); };
        send.Click += (_, _) => Finish();
        Shown += (_, _) => { if (!_mic.Start()) { MessageBox.Show(this, "No microphone available."); Close(); } else _t.Start(); };
        FormClosed += (_, _) => { _t.Stop(); _mic.Dispose(); };
    }

    void Finish()
    {
        _t.Stop();
        short[] all; lock (_pcm) all = _pcm.ToArray();
        Result = all.Length < Adpcm.SampleRate / 2 ? null : Adpcm.PackVoice(all, all.Length);
        if (Result == null) MessageBox.Show(this, "Too short.");
        Close();
    }
}
