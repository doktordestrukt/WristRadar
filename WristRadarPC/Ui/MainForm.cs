using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using WristRadarPC.Core;

namespace WristRadarPC.Ui;

public sealed class MainForm : Form
{
    readonly Engine E;
    readonly System.Windows.Forms.Timer _uiTimer = new() { Interval = 150 };
    bool _dirty = true;

    // left column
    readonly Panel _left = new() { Dock = DockStyle.Left, Width = 290, BackColor = Theme.Surface, Padding = new Padding(16), AutoScroll = true };
    readonly Control _profile = new DbPanel { Height = 118, Dock = DockStyle.Top };
    readonly Control _status = new DbPanel { Height = 214, Dock = DockStyle.Top };
    readonly WrButton _editName = new("Edit name"), _pickAvatar = new("Picture…");
    readonly WrButton _refresh = new("Refresh connection") { Accent = true };
    readonly WrButton _unpair = new("Unpair watch") { DangerStyle = true };
    readonly WrButton _log = new("Security log"), _bio = new("Edit bio");
    readonly CheckBox _soundChk = new() { Text = "Alert sound", ForeColor = Theme.Text, AutoSize = true };
    readonly CheckBox _musicChk = new() { Text = "Hacker music", ForeColor = Theme.Text, AutoSize = true };

    // right
    readonly Panel _right = new() { Dock = DockStyle.Fill, BackColor = Theme.Bg, Padding = new Padding(16) };
    readonly WrButton _tabChat = new("Chat"), _tabNearby = new("Nearby watches");
    readonly Panel _chatPage = new() { Dock = DockStyle.Fill, BackColor = Theme.Bg };
    readonly Panel _nearbyPage = new() { Dock = DockStyle.Fill, BackColor = Theme.Bg, Visible = false };
    readonly FlowLayoutPanel _flow = new() { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, BackColor = Theme.Bg };
    readonly TextBox _input = new() { BorderStyle = BorderStyle.FixedSingle, BackColor = Theme.Surface2, ForeColor = Theme.Text, Font = new Font("Segoe UI", 11f) };
    readonly WrButton _send = new("Send") { Accent = true, Width = 90 }, _img = new("Picture…") { Width = 100 };
    readonly Label _chatEmpty = new() { ForeColor = Theme.Dim, AutoSize = false, Height = 60, Dock = DockStyle.Top, TextAlign = ContentAlignment.MiddleCenter };
    readonly Dictionary<string, Bubble> _bubbles = new();

    readonly RadarView _radar = new() { Height = 220, Dock = DockStyle.Top };
    readonly ListBox _list = new() { DrawMode = DrawMode.OwnerDrawFixed, ItemHeight = 58, BorderStyle = BorderStyle.None, BackColor = Theme.Bg, ForeColor = Theme.Text, Dock = DockStyle.Fill };
    readonly WrButton _pairBtn = new("Pair with selected watch") { Accent = true };
    readonly Panel _pairCard = new DbPanel { Dock = DockStyle.Fill, Visible = false };
    readonly Label _pairTitle = new() { ForeColor = Theme.Primary, Font = Theme.MonoBold, Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter };
    readonly Label _code = new() { ForeColor = Theme.Text, Font = Theme.Code, Dock = DockStyle.Top, Height = 90, TextAlign = ContentAlignment.MiddleCenter };
    readonly Label _pairNote = new() { ForeColor = Theme.Dim, Font = Theme.Ui, Dock = DockStyle.Top, Height = 50, TextAlign = ContentAlignment.MiddleCenter };
    readonly WrButton _match = new("Codes match") { Accent = true, Width = 180 }, _differ = new("Codes differ") { DangerStyle = true, Width = 180 },
        _cancelPair = new("Cancel") { Width = 180 }, _donePair = new("Done") { Accent = true, Width = 180 };

    readonly BlackboxOverlay _overlay = new();
    readonly CallOverlay _callUi;
    readonly WarningOverlay _warn = new();
    readonly TopBanner _banner = new();
    readonly Label _chatHead = new() { Dock = DockStyle.Top, Height = 34, ForeColor = Theme.Dim, Font = new Font("Segoe UI", 10f), TextAlign = ContentAlignment.MiddleLeft };
    readonly WrButton _callBtn = new("📞 Call") { Accent = true, Width = 110, Dock = DockStyle.Right };
    readonly WrButton _voiceBtn = new("🎤 Voice") { Width = 100 };

    public MainForm()
    {
        Text = "WristRadar PC";
        BackColor = Theme.Bg; ForeColor = Theme.Text; Font = Theme.Ui;
        MinimumSize = new Size(940, 640); Size = new Size(1080, 720);
        StartPosition = FormStartPosition.CenterScreen;
        DoubleBuffered = true;
        E = new Engine();
        _callUi = new CallOverlay(E);

        BuildLeft(); BuildRight();
        Controls.Add(_right); Controls.Add(_left); Controls.Add(_warn); Controls.Add(_callUi); Controls.Add(_overlay); Controls.Add(_banner);
        _overlay.BringToFront();
        E.WarningRaised += w => _warn.Show(w);
        _warn.Dismissed += () => E.WarningDismissed();
        _callUi.VoicemailRequested += () => RecordVoice("Voicemail");
        _banner.Clicked += () => { ShowPage(true); Activate(); };
        VoicePlayback.Changed += () => BeginInvoke(() => { foreach (var b in _bubbles.Values) b.Invalidate(); });
        var sec = new System.Windows.Forms.Timer { Interval = 1000 }; sec.Tick += (_, _) => _dirty = true; sec.Start();

        E.Changed += () => _dirty = true;
        E.Incoming += m =>
        {
            if (ContainsFocus && _chatPage.Visible) return;
            FlashWindow();
            _banner.Show(E.S.Trust?.PartnerName ?? "Partner", m.IsAudio ? "🎤 Voice message" : m.IsImage ? "📷 Photo" : m.Text, Theme.FromJpeg(E.S.Trust?.PartnerAvatar));
        };
        E.AlertRaised += a => _overlay.Show(a, E.S.Profile.Sound, E.S.Profile.Music);
        _overlay.Dismissed += () => E.AlertDismissed();
        _uiTimer.Tick += (_, _) => { if (_dirty) { _dirty = false; Render(); } };
        Load += (_, _) => { E.Start(); _uiTimer.Start(); ShowPage(E.Paired); Render(); };
        FormClosing += (_, _) => E.Shutdown();
    }

    // ------------------------------------------------------------------ layout
    void BuildLeft()
    {
        var title = new Label { Text = "WRISTRADAR", Font = Theme.Title, ForeColor = Theme.Primary, Dock = DockStyle.Top, Height = 40 };
        var sub = new Label { Text = "PC COMPANION · V1", Font = new Font("Consolas", 8f), ForeColor = Theme.Dim, Dock = DockStyle.Top, Height = 22 };
        _profile.Paint += PaintProfile;
        _profile.Cursor = Cursors.Hand;
        _profile.Click += (_, _) => PickAvatar();
        var profBtns = Row(_editName, _pickAvatar);
        _editName.Click += (_, _) => { var n = Prompt.Ask(this, "Username", "Your display name (shown on the watch):", E.S.Profile.Name); if (n != null) E.SaveProfile(n, null, false); };
        _pickAvatar.Click += (_, _) => PickAvatar();
        _status.Paint += PaintStatus;
        _refresh.Click += (_, _) => E.Refresh();
        _unpair.Click += (_, _) =>
        {
            if (MessageBox.Show(this, "Delete the trusted keys and the conversation on this PC? You will need to pair again.", "Unpair", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes) E.Unpair();
        };
        _log.Click += (_, _) => ShowLog();
        _bio.Click += (_, _) => { var b = Prompt.Ask(this, "Bio", "A short bio shown to your partner:", E.S.Profile.Bio, 120); if (b != null) { E.S.Profile.Bio = b; E.SaveProfile(null, null, false); } };
        _soundChk.Checked = E.S.Profile.Sound; _musicChk.Checked = E.S.Profile.Music;
        _soundChk.CheckedChanged += (_, _) => { E.S.Profile.Sound = _soundChk.Checked; E.S.SaveProfile(); };
        _musicChk.CheckedChanged += (_, _) => { E.S.Profile.Music = _musicChk.Checked; E.S.SaveProfile(); };
        var chk = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 30, BackColor = Theme.Surface };
        chk.Controls.Add(_soundChk); chk.Controls.Add(_musicChk);
        var fp = new Label { Text = "Fingerprint " + E.S.Fingerprint, Font = new Font("Consolas", 8f), ForeColor = Theme.Dim, Dock = DockStyle.Bottom, Height = 22 };

        // Dock=Top stacks in reverse add order
        foreach (var c in new Control[] { chk, Spacer(6), Full(_bio), Spacer(6), Full(_log), Spacer(14), Full(_unpair), Spacer(6), Full(_refresh), Spacer(10), _status, Spacer(10), profBtns, _profile, sub, title })
            _left.Controls.Add(c);
        _left.Controls.Add(fp);
    }

    void BuildRight()
    {
        var tabs = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 46, BackColor = Theme.Bg };
        _tabChat.Width = 140; _tabNearby.Width = 170;
        tabs.Controls.Add(_tabChat); tabs.Controls.Add(_tabNearby);
        _tabChat.Click += (_, _) => ShowPage(true);
        _tabNearby.Click += (_, _) => ShowPage(false);

        // chat page
        var inputRow = new Panel { Dock = DockStyle.Bottom, Height = 46, BackColor = Theme.Bg, Padding = new Padding(0, 8, 0, 0) };
        _send.Dock = DockStyle.Right; _img.Dock = DockStyle.Right; _input.Dock = DockStyle.Fill;
        inputRow.Controls.Add(_input); inputRow.Controls.Add(Spacer(8, DockStyle.Right)); inputRow.Controls.Add(_img); inputRow.Controls.Add(Spacer(8, DockStyle.Right)); inputRow.Controls.Add(_send);
        _send.Click += async (_, _) => { var t = _input.Text; _input.Clear(); E.NotifyTyping(false); await E.SendText(t); };
        _input.KeyDown += async (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; var t = _input.Text; _input.Clear(); E.NotifyTyping(false); await E.SendText(t); } };
        _img.Click += (_, _) => PickImageToSend();
        _voiceBtn.Dock = DockStyle.Right;
        inputRow.Controls.Add(Spacer(8, DockStyle.Right)); inputRow.Controls.Add(_voiceBtn);
        _voiceBtn.Click += (_, _) => RecordVoice("Voice message");
        _input.TextChanged += (_, _) => E.NotifyTyping(_input.Text.Length > 0);
        _callBtn.Click += (_, _) => E.StartCall();
        var head = new Panel { Dock = DockStyle.Top, Height = 40, BackColor = Theme.Bg, Padding = new Padding(0, 0, 0, 6) };
        head.Controls.Add(_chatHead); head.Controls.Add(_callBtn);
        _flow.Resize += (_, _) => { foreach (var b in _bubbles.Values) b.Relayout(); };
        _chatPage.Controls.Add(_flow); _chatPage.Controls.Add(_chatEmpty); _chatPage.Controls.Add(head); _chatPage.Controls.Add(inputRow);

        // nearby page
        _list.DrawItem += DrawNearby;
        _pairBtn.Dock = DockStyle.Bottom;
        _pairBtn.Click += (_, _) => { if (_list.SelectedItem is NearbyWatch n) E.StartPair(n.DiscoveryId); };
        _list.DoubleClick += (_, _) => { if (_list.SelectedItem is NearbyWatch n) E.StartPair(n.DiscoveryId); };
        var hint = new Label
        {
            Text = "Watch: WristRadar → Connect to nearby watches. Then select it here, press Pair, and accept on the watch.",
            ForeColor = Theme.Dim, Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.MiddleCenter,
        };
        var pairBtns = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 100, FlowDirection = FlowDirection.LeftToRight, BackColor = Theme.Surface, Padding = new Padding(40, 10, 0, 0) };
        pairBtns.Controls.AddRange(new Control[] { _match, _differ, _cancelPair, _donePair });
        _match.Click += (_, _) => E.ConfirmPair(true);
        _differ.Click += (_, _) => E.ConfirmPair(false);
        _cancelPair.Click += (_, _) => E.CancelPair();
        _donePair.Click += (_, _) => { var ok = E.Pair == PairPhase.Success; E.DismissPair(); if (ok) ShowPage(true); };
        _pairCard.BackColor = Theme.Surface;
        _pairCard.Controls.Add(pairBtns); _pairCard.Controls.Add(_pairNote); _pairCard.Controls.Add(_code); _pairCard.Controls.Add(_pairTitle);
        _nearbyPage.Controls.Add(_list); _nearbyPage.Controls.Add(_pairCard); _nearbyPage.Controls.Add(_pairBtn); _nearbyPage.Controls.Add(hint); _nearbyPage.Controls.Add(_radar);

        _right.Controls.Add(_chatPage); _right.Controls.Add(_nearbyPage); _right.Controls.Add(tabs);
    }

    static Control Spacer(int h, DockStyle d = DockStyle.Top) => new Panel { Height = h, Width = h, Dock = d, BackColor = Color.Transparent };
    static Control Full(Control c) { c.Dock = DockStyle.Top; return c; }
    static Control Row(WrButton a, WrButton b)
    {
        var p = new TableLayoutPanel { Dock = DockStyle.Top, Height = 40, ColumnCount = 2, BackColor = Theme.Surface };
        p.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50)); p.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        a.Dock = DockStyle.Fill; b.Dock = DockStyle.Fill;
        p.Controls.Add(a, 0, 0); p.Controls.Add(b, 1, 0);
        return p;
    }

    void ShowPage(bool chat)
    {
        _chatPage.Visible = chat; _nearbyPage.Visible = !chat;
        _tabChat.Accent = chat; _tabNearby.Accent = !chat;
        _tabChat.Invalidate(); _tabNearby.Invalidate();
    }

    // ------------------------------------------------------------------ rendering
    void Render()
    {
        _profile.Invalidate(); _status.Invalidate();
        _unpair.Enabled = E.Paired; _refresh.Enabled = E.Paired;
        _send.Enabled = _img.Enabled = _input.Enabled = _voiceBtn.Enabled = E.Paired;
        _callBtn.Enabled = E.Connected && E.Call == CallPhase.Idle;
        var pn = E.S.Trust?.PartnerName ?? "Partner";
        _chatHead.Text = !E.Paired ? "" : E.PartnerTyping ? $"{pn} is typing…" : E.Connected ? $"{pn} · Online" : $"{pn} · Last online {Rel.Ago(E.S.Trust?.PartnerLastSeen ?? 0)}";
        _chatHead.ForeColor = E.PartnerTyping ? Theme.Primary : E.Connected ? Theme.Good : Theme.Dim;

        // nearby list
        var items = E.Nearby.Values.OrderByDescending(n => n.Rssi).ToList();
        var sel = (_list.SelectedItem as NearbyWatch)?.DiscoveryId;
        _list.BeginUpdate(); _list.Items.Clear();
        foreach (var n in items) _list.Items.Add(n);
        var idx = items.FindIndex(n => n.DiscoveryId == sel);
        if (idx >= 0) _list.SelectedIndex = idx; else if (items.Count > 0) _list.SelectedIndex = 0;
        _list.EndUpdate();
        _radar.Blips = items.Select(n => ((float)((n.DiscoveryId & 0xffff) / 65535.0 * Math.PI * 2), (float)Math.Clamp(Math.Pow(10, (-62 - n.Rssi) / 23.0) / 15, 0.2, 0.95))).ToList();
        _pairBtn.Enabled = items.Count > 0 && !E.Paired && E.Pair == PairPhase.Idle;
        _pairBtn.Text = E.Paired ? "Already paired (unpair first)" : "Pair with selected watch";

        // pairing card
        bool pairing = E.Pair != PairPhase.Idle;
        _pairCard.Visible = pairing; _list.Visible = !pairing;
        if (pairing)
        {
            _pairTitle.Text = E.Pair switch
            {
                PairPhase.Connecting => $"CONNECTING TO {E.PairName.ToUpper()}",
                PairPhase.Waiting => "WAITING FOR THE WATCH",
                PairPhase.Verify => "VERIFICATION CODE",
                PairPhase.Success => "PAIRED",
                _ => "PAIRING FAILED",
            };
            _code.Text = E.Pair == PairPhase.Verify ? E.PairCode : E.Pair == PairPhase.Success ? "✓" : "";
            _code.ForeColor = E.Pair == PairPhase.Success ? Theme.Good : Theme.Text;
            _pairNote.Text = E.PairNote;
            _match.Visible = _differ.Visible = E.Pair == PairPhase.Verify && !E.PairMyConfirmed;
            _cancelPair.Visible = E.Pair is PairPhase.Connecting or PairPhase.Waiting or PairPhase.Verify;
            _donePair.Visible = E.Pair is PairPhase.Success or PairPhase.Failed;
        }

        // chat bubbles
        List<Msg> msgs; lock (E.S.Messages) msgs = E.S.Messages.OrderBy(m => m.LocalTime).ToList();
        _chatEmpty.Visible = msgs.Count == 0;
        _chatEmpty.Text = E.Paired ? "No messages yet. Say hi 👋" : "Not paired. Go to Nearby watches to pair with your watch.";
        bool added = false;
        _flow.SuspendLayout();
        foreach (var id in _bubbles.Keys.Where(k => msgs.All(m => m.Id != k)).ToList()) { _flow.Controls.Remove(_bubbles[id]); _bubbles[id].Dispose(); _bubbles.Remove(id); }
        foreach (var m in msgs)
        {
            if (_bubbles.TryGetValue(m.Id, out var b)) { b.Update(m); continue; }
            b = new Bubble(m, id => Theme.FromJpeg(E.S.LoadImage(id)));
            if (m.IsAudio) b.Click += (_, _) => VoicePlayback.Toggle(m.Id, E.S.LoadImage(m.Id));
            else if (m.IsImage) b.Click += (_, _) => ShowImage(m.Id);
            _bubbles[m.Id] = b; _flow.Controls.Add(b); b.Relayout(); added = true;
        }
        _flow.ResumeLayout();
        if (added && _flow.Controls.Count > 0) _flow.ScrollControlIntoView(_flow.Controls[^1]);
    }

    void PaintProfile(object? s, PaintEventArgs e)
    {
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        using var img = Theme.FromJpeg(E.S.Profile.Avatar);
        Theme.DrawAvatar(g, new Rectangle(6, 10, 84, 84), img, E.S.Profile.Name);
        TextRenderer.DrawText(g, E.S.Profile.Name, new Font("Segoe UI", 14f, FontStyle.Bold), new Rectangle(100, 26, _profile.Width - 100, 30), Theme.Text, TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(g, "Click avatar to change", Theme.UiSmall, new Rectangle(102, 58, _profile.Width - 102, 18), Theme.Dim, TextFormatFlags.EndEllipsis);
    }

    void PaintStatus(object? s, PaintEventArgs e)
    {
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        var c = Theme.PhaseColor(E.Phase);
        using (var bg = Theme.Round(new RectangleF(0, 0, _status.Width - 1, _status.Height - 1), 14)) using (var b = new SolidBrush(Theme.Surface2)) g.FillPath(b, bg);
        using (var halo = new SolidBrush(Color.FromArgb(60, c))) g.FillEllipse(halo, 12, 14, 18, 18);
        using (var dot = new SolidBrush(c)) g.FillEllipse(dot, 16, 18, 10, 10);
        TextRenderer.DrawText(g, E.Phase, Theme.MonoBold, new Point(36, 15), c);
        var t = E.S.Trust;
        int y = 42;
        void Line(string txt, Color col) { TextRenderer.DrawText(g, txt, Theme.UiSmall, new Rectangle(14, y, _status.Width - 24, 18), col, TextFormatFlags.EndEllipsis); y += 18; }
        if (t != null)
        {
            Line("Partner: " + (t.PartnerName ?? "watch"), Theme.Text);
            Line(E.PartnerTyping ? "typing…" : E.Connected ? "Online" : "Last online " + Rel.Ago(t.PartnerLastSeen), E.Connected ? Theme.Good : Theme.Dim);
            if (t.PartnerBio.Length > 0) Line("“" + t.PartnerBio + "”", Theme.Dim);
            Line("Their phone: " + (E.PartnerPhone switch { true => "📱 connected", false => "not connected", null => "unknown" }), Theme.Dim);
            Line($"Signal: {(E.PartnerRssi is double r ? $"{r:0} dBm · {Theme.Signal(r)}" : "--")}", Theme.Dim);
            Line("Distance: " + Theme.Distance(E.PartnerRssi) + " (rough)", Theme.Dim);
            Line("Watch battery: " + (E.RemoteBattery is int bat ? bat + "%" : "--"), Theme.Dim);
        }
        else Line("Not paired", Theme.Dim);
        if (!string.IsNullOrEmpty(E.Detail)) Line(E.Detail, Theme.Dim);
    }

    void DrawNearby(object? s, DrawItemEventArgs e)
    {
        if (e.Index < 0) return;
        var n = (NearbyWatch)_list.Items[e.Index];
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
        bool sel = (e.State & DrawItemState.Selected) != 0;
        using (var bg = new SolidBrush(sel ? Theme.Surface2 : Theme.Bg)) g.FillRectangle(bg, e.Bounds);
        using var img = Theme.FromJpeg(n.Avatar);
        Theme.DrawAvatar(g, new Rectangle(e.Bounds.X + 8, e.Bounds.Y + 8, 42, 42), img, n.Name);
        TextRenderer.DrawText(g, n.Name ?? "Identifying…", new Font("Segoe UI", 11f, FontStyle.Bold), new Point(e.Bounds.X + 60, e.Bounds.Y + 9), Theme.Text);
        TextRenderer.DrawText(g, $"{Theme.Distance(n.Rssi)} · {n.Rssi:0} dBm · {Theme.Signal(n.Rssi)}", Theme.UiSmall, new Point(e.Bounds.X + 62, e.Bounds.Y + 33), Theme.Dim);
    }

    // ------------------------------------------------------------------ actions
    void PickAvatar()
    {
        using var d = new OpenFileDialog { Filter = "Pictures|*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.webp", Title = "Choose a profile picture" };
        if (d.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            using var src = Image.FromFile(d.FileName);
            E.SaveProfile(null, Jpeg(Square(src, 160), 82), true);
        }
        catch (Exception ex) { MessageBox.Show(this, "Could not open that picture: " + ex.Message); }
    }

    void PickImageToSend()
    {
        using var d = new OpenFileDialog { Filter = "Pictures|*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.webp", Title = "Send a picture to your watch" };
        if (d.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            using var src = Image.FromFile(d.FileName);
            using var scaled = Fit(src, 480);
            E.SendImage(Jpeg(scaled, 72), scaled.Width, scaled.Height);
        }
        catch (Exception ex) { MessageBox.Show(this, "Could not open that picture: " + ex.Message); }
    }

    static Bitmap Fit(Image src, int max)
    {
        double s = Math.Min(1.0, (double)max / Math.Max(src.Width, src.Height));
        var b = new Bitmap(Math.Max(1, (int)(src.Width * s)), Math.Max(1, (int)(src.Height * s)));
        using var g = Graphics.FromImage(b);
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.DrawImage(src, 0, 0, b.Width, b.Height);
        return b;
    }

    static Bitmap Square(Image src, int size)
    {
        int edge = Math.Min(src.Width, src.Height);
        var b = new Bitmap(size, size);
        using var g = Graphics.FromImage(b);
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.DrawImage(src, new Rectangle(0, 0, size, size), new Rectangle((src.Width - edge) / 2, (src.Height - edge) / 2, edge, edge), GraphicsUnit.Pixel);
        return b;
    }

    static byte[] Jpeg(Image img, long quality)
    {
        var enc = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        using var p = new EncoderParameters(1);
        p.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, quality);
        using var ms = new MemoryStream();
        img.Save(ms, enc, p);
        return ms.ToArray();
    }

    void ShowImage(string id)
    {
        var img = Theme.FromJpeg(E.S.LoadImage(id));
        if (img == null) return;
        var f = new Form { Text = "Picture", BackColor = Color.Black, Size = new Size(Math.Min(900, img.Width + 40), Math.Min(900, img.Height + 60)), StartPosition = FormStartPosition.CenterParent };
        f.Controls.Add(new PictureBox { Image = img, Dock = DockStyle.Fill, SizeMode = PictureBoxSizeMode.Zoom });
        f.FormClosed += (_, _) => img.Dispose();
        f.Show(this);
    }

    void ShowLog()
    {
        if (E.S.Profile.Sound) Sound.Play("glitch1.mp3");
        var f = new Form { Text = "Security log", BackColor = Theme.Bg, Size = new Size(720, 460), StartPosition = FormStartPosition.CenterParent };
        var box = new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BackColor = Color.Black, ForeColor = Color.FromArgb(0x39, 0xFF, 0x14), Font = Theme.Mono, BorderStyle = BorderStyle.None };
        List<SecurityEvent> log; lock (E.S.SecurityLog) log = E.S.SecurityLog.ToList();
        box.Text = log.Count == 0 ? "> No unauthorised access detected." :
            string.Join("\r\n", log.Select(ev => $"> {DateTimeOffset.FromUnixTimeMilliseconds(ev.Time).ToLocalTime():yyyy-MM-dd HH:mm:ss}  {ev.Type}\r\n    {ev.Detail}\r\n    {ev.Device}"));
        var clear = new WrButton("Clear log") { DangerStyle = true, Dock = DockStyle.Bottom };
        clear.Click += (_, _) => { lock (E.S.SecurityLog) E.S.SecurityLog.Clear(); E.S.SaveSecurityLog(); box.Text = "> Log cleared."; };
        f.Controls.Add(box); f.Controls.Add(clear);
        f.Show(this);
    }

    void RecordVoice(string title)
    {
        if (!E.Paired) return;
        using var d = new VoiceRecordDialog(title);
        d.ShowDialog(this);
        if (d.Result != null) { E.SendVoice(d.Result); ShowPage(true); }
    }

    [DllImport("user32.dll")] static extern bool FlashWindow(IntPtr hwnd, bool invert);
    void FlashWindow() { try { FlashWindow(Handle, true); System.Media.SystemSounds.Asterisk.Play(); } catch { } }

    sealed class DbPanel : Panel
    {
        public DbPanel() { DoubleBuffered = true; BackColor = Theme.Surface; SetStyle(ControlStyles.ResizeRedraw, true); }
    }
}

static class Prompt
{
    public static string? Ask(IWin32Window owner, string title, string label, string initial, int maxLen = 20)
    {
        using var f = new Form { Text = title, Size = new Size(420, 170), FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, StartPosition = FormStartPosition.CenterParent, BackColor = Theme.Surface, ForeColor = Theme.Text };
        var l = new Label { Text = label, Left = 14, Top = 14, Width = 380 };
        var t = new TextBox { Text = initial, Left = 14, Top = 40, Width = 375, MaxLength = maxLen, BackColor = Theme.Surface2, ForeColor = Theme.Text };
        var ok = new Button { Text = "Save", Left = 220, Top = 80, Width = 80, DialogResult = DialogResult.OK };
        var cancel = new Button { Text = "Cancel", Left = 308, Top = 80, Width = 80, DialogResult = DialogResult.Cancel };
        f.Controls.AddRange(new Control[] { l, t, ok, cancel });
        f.AcceptButton = ok; f.CancelButton = cancel;
        return f.ShowDialog(owner) == DialogResult.OK && t.Text.Trim().Length > 0 ? t.Text.Trim() : null;
    }
}
