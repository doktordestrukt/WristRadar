using System.Threading.Channels;
using Windows.Devices.Bluetooth;
using WristRadarPC.Ble;
using WristRadarPC.Crypto;

namespace WristRadarPC.Core;

public enum PairPhase { Idle, Connecting, Waiting, Verify, Success, Failed }

public sealed class NearbyWatch
{
    public int DiscoveryId;
    public ulong Address;
    public BluetoothAddressType AddressType;
    public double Rssi;
    public long LastSeen;
    public string? Name;
    public byte[]? Avatar;
    public bool Fetched;
    public int FetchFails;
}

public sealed record Alert(string Title, string Detail, string Device, DateTime Time, bool DeniedHere = false, bool Preview = false);

/// <summary>
/// The PC companion is always the BLE central (GATT client): it scans, connects, pairs as the
/// initiator and runs the client side of the hybrid handshake. All state lives on the UI thread;
/// BLE callbacks are marshalled onto it, mirroring the watch's single-threaded engine.
/// </summary>
public sealed partial class Engine
{
    readonly SynchronizationContext _ui;
    public Store S { get; } = new();
    readonly Scanner _scanner = new();
    readonly System.Windows.Forms.Timer _timer = new() { Interval = 2000 };

    public event Action? Changed;
    public event Action<Msg>? Incoming;
    public event Action<Alert>? AlertRaised;

    public string Phase { get; private set; } = "STANDBY";
    public string Detail { get; private set; } = "";
    public double? PartnerRssi { get; private set; }
    public int? RemoteBattery { get; private set; }
    public bool Connected => _session != null && !_session.Closed;
    public bool ScannerOk { get; private set; } = true;

    public Dictionary<int, NearbyWatch> Nearby { get; } = new();
    readonly int _myDiscoveryId = Random.Shared.Next(1, int.MaxValue);
    bool _fetching;

    // partner link
    ulong? _partnerAddr; BluetoothAddressType _partnerType; long _partnerSeen;
    BleLink? _session; SecureChannel? _chan; Channel<byte[]>? _inbox;
    readonly SemaphoreSlim _secLock = new(1, 1);
    bool _connecting; int _fails; long _nextAttempt; long _tokenWindow = -1; List<byte[]> _tokens = new();
    readonly Dictionary<int, (HashSet<byte> types, TaskCompletionSource<byte[]?> tcs)> _waiters = new();
    readonly HashSet<int> _authenticated = new();
    readonly Dictionary<string, long> _lastAttempt = new();

    // pairing
    public PairPhase Pair { get; private set; } = PairPhase.Idle;
    public string PairName { get; private set; } = "";
    public string PairCode { get; private set; } = "";
    public string PairNote { get; private set; } = "";
    public bool PairMyConfirmed { get; private set; }
    PairingInitiator? _pi; BleLink? _pairLink; byte[]? _peerConfirmSig;

    // blackbox
    Alert? _alert; DateTime _lastAlert = DateTime.MinValue;

    public Engine()
    {
        _ui = SynchronizationContext.Current ?? throw new InvalidOperationException("Engine must be created on the UI thread");
        _scanner.Seen += a => _ui.Post(_ => OnSeen(a), null);
        _timer.Tick += (_, _) => Tick();
    }

    public bool Paired => S.Trust != null;
    void Notify() => Changed?.Invoke();
    void SetPhase(string p, string d = "") { Phase = p; Detail = d; Log($"{p} | {d}"); Notify(); }

    /// <summary>Diagnostics only: phases and errors, never message content or keys.</summary>
    public static void Log(string line)
    {
        try { File.AppendAllText(Path.Combine(Store.Dir, "debug.log"), $"{DateTime.Now:HH:mm:ss.fff} {line}{Environment.NewLine}"); } catch { }
    }
    static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public void Start()
    {
        try { _scanner.Start(); ScannerOk = true; }
        catch (Exception e) { ScannerOk = false; SetPhase("BLUETOOTH ERROR", "Cannot scan: " + e.Message); }
        _timer.Start();
        SetPhase(Paired ? "SEARCHING" : "NOT PAIRED", Paired ? "Looking for your watch…" : "Open WristRadar → Connect to nearby watches on the watch");
    }

    public void Shutdown()
    {
        _timer.Stop(); _scanner.Stop();
        _session?.Dispose(); _pairLink?.Dispose();
    }

    // =============================================================== scanning

    void RefreshTokens()
    {
        var w = Advert.CurrentWindow();
        if (w == _tokenWindow || S.Trust == null) return;
        _tokenWindow = w;
        var key = Advert.AdvertKeyFor(S.Trust.Psk, S.Trust.PartnerId);
        _tokens = new() { Advert.Token(key, w - 1), Advert.Token(key, w), Advert.Token(key, w + 1) };
    }

    void OnSeen(AdvertSeen a)
    {
        if (S.Trust != null)
        {
            RefreshTokens();
            if (_tokens.Any(t => t.AsSpan().SequenceEqual(a.Token)))
            {
                _partnerAddr = a.Address; _partnerType = a.AddressType; _partnerSeen = Now();
                PartnerRssi = PartnerRssi == null ? a.Rssi : PartnerRssi + 0.25 * (a.Rssi - PartnerRssi);
                if (!Connected && !_connecting && Phase is "SEARCHING" or "CONNECTION FAILED" or "STANDBY") SetPhase("DEVICE FOUND", "Watch in range");
                MaybeConnect();
                Notify();
            }
        }
        if (a.Discoverable && a.DiscoveryId != 0)
        {
            if (!Nearby.TryGetValue(a.DiscoveryId, out var n)) Nearby[a.DiscoveryId] = n = new NearbyWatch { DiscoveryId = a.DiscoveryId, Rssi = a.Rssi };
            n.Address = a.Address; n.AddressType = a.AddressType; n.LastSeen = Now();
            n.Rssi += 0.25 * (a.Rssi - n.Rssi);
            Notify();
            FetchProfiles();
        }
    }

    async void FetchProfiles()
    {
        if (_fetching || Pair is PairPhase.Connecting or PairPhase.Waiting or PairPhase.Verify) return;
        var n = Nearby.Values.Where(x => !x.Fetched && x.FetchFails < 3).OrderByDescending(x => x.Rssi).FirstOrDefault();
        if (n == null) return;
        _fetching = true;
        try
        {
            var link = await Open(n.Address, n.AddressType, 10);
            if (link == null) { n.FetchFails++; return; }
            var resp = await Request(link, Ids.Frame(F.ProfileReq), new() { F.ProfileResp }, 6000);
            link.Dispose();
            if (resp == null) { n.FetchFails++; return; }
            var r = new In(resp, 1);
            if (r.I() != n.DiscoveryId) { n.FetchFails++; return; }
            n.Name = Clean(r.Str(200)); n.Avatar = r.Bytes(30000); n.Fetched = true;
            Log("profile fetched for discovery id " + n.DiscoveryId);
            Notify();
        }
        catch { n.FetchFails++; }
        finally { _fetching = false; }
        FetchProfiles();
    }

    static string? Clean(string s) { var t = new string(s.Where(c => !char.IsControl(c)).ToArray()).Trim(); return t.Length == 0 ? null : t[..Math.Min(20, t.Length)]; }

    // =============================================================== links

    async Task<BleLink?> Open(ulong addr, BluetoothAddressType type, int seconds)
    {
        BleLink? l = null;
        for (int attempt = 0; attempt < 3 && l == null; attempt++)
        {
            if (attempt > 0) await Task.Delay(1500);   // let a previous connection finish closing
            l = await BleLink.ConnectAsync(addr, type, TimeSpan.FromSeconds(seconds));
        }
        if (l == null) return null;
        l.Frame += (link, f) => _ui.Post(_ => OnFrame(link, f), null);
        l.ClosedEvent += link => _ui.Post(_ => OnClosed(link), null);
        return l;
    }

    async Task<byte[]?> Request(BleLink link, byte[] frame, HashSet<byte> types, int timeoutMs)
    {
        var tcs = new TaskCompletionSource<byte[]?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _waiters[link.Id] = (types, tcs);
        if (!await link.SendAsync(frame)) { _waiters.Remove(link.Id); return null; }
        var done = await Task.WhenAny(tcs.Task, Task.Delay(timeoutMs));
        _waiters.Remove(link.Id);
        return done == tcs.Task ? tcs.Task.Result : null;
    }

    void OnFrame(BleLink l, byte[] f)
    {
        if (f.Length == 0 || l.Closed) return;
        if (_waiters.TryGetValue(l.Id, out var w) && w.types.Contains(f[0])) { _waiters.Remove(l.Id); w.tcs.TrySetResult(f); return; }
        switch (f[0])
        {
            case F.PairResponse or F.PairConfirm or F.PairAbort: OnPairFrame(l, f); break;
            case F.Secure:
                if (l != _session || _chan == null) return;
                var plain = _chan.Open(f[1..]);
                if (plain == null)
                {
                    if (_chan.LastError == RecordError.Replay) Report("REPLAYED MESSAGE", "Replayed encrypted message rejected", l);
                    else Report("TAMPERED MESSAGE", "Modified encrypted message rejected (invalid authentication tag)", l);
                    return;
                }
                _inbox?.Writer.TryWrite(plain);
                break;
        }
    }

    void OnClosed(BleLink l)
    {
        if (_waiters.Remove(l.Id, out var w)) w.tcs.TrySetResult(null);
        if (l == _pairLink && Pair is PairPhase.Connecting or PairPhase.Waiting or PairPhase.Verify) PairFail("Connection to the watch was lost.");
        if (l == _session)
        {
            _session = null; _chan?.Destroy(); _chan = null; _inbox?.Writer.TryComplete(); _inbox = null;
            OnLinkLostExtras();
            RemoteBattery = null;
            _nextAttempt = Now() + 1500;
            SetPhase(Paired ? "SEARCHING" : "NOT PAIRED", "Link lost – reconnecting…");
        }
    }

    // =============================================================== partner connection

    void MaybeConnect()
    {
        if (S.Trust == null || Connected || _connecting || _partnerAddr == null) return;
        if (Pair is PairPhase.Connecting or PairPhase.Waiting or PairPhase.Verify) return;
        if (Now() - _partnerSeen > 10_000 || Now() < _nextAttempt) return;
        _ = ConnectPartner();
    }

    async Task ConnectPartner()
    {
        _connecting = true;
        SetPhase("CONNECTING", "Opening Bluetooth link…");
        var link = await Open(_partnerAddr!.Value, _partnerType, 15);
        if (link == null) { ConnectFailed("Could not connect"); return; }
        await Handshake(link);
    }

    async Task Handshake(BleLink link)
    {
        _connecting = true;
        var t = S.Trust;
        if (t == null) { link.Dispose(); _connecting = false; return; }
        SetPhase("AUTHENTICATING", "Verifying watch identity (X25519 + ML-KEM)…");
        var hc = new HandshakeClient(S, t.PartnerId, t.Psk);
        var resp = await Request(link, Ids.Frame(F.HsHello, hc.Hello()), new() { F.HsServer, F.HsReject, F.Blackbox }, 15_000);
        if (resp != null && resp[0] == F.Blackbox)
        {
            link.Dispose();
            RaiseAlert(new Alert("ACCESS DENIED", "This PC tried to open a protected WristRadar channel and was refused.", link.DeviceName ?? "UNKNOWN DEVICE", DateTime.Now, DeniedHere: true));
            ConnectFailed("Access denied by the watch"); return;
        }
        if (resp == null || resp[0] == F.HsReject)
        {
            link.Dispose();
            ConnectFailed(resp == null ? "No response from the watch" : "The watch refused the session (pairing removed on the watch?)");
            return;
        }
        var res = hc.OnServer(resp[1..]);
        if (res == null)
        {
            Report("IMPERSONATION", "Device using your watch's radio ID failed the identity check", link);
            await link.SendAsync(Ids.Frame(F.Blackbox));
            link.Dispose();
            ConnectFailed("Authentication failed: not your trusted watch");
            return;
        }
        if (!await link.SendAsync(Ids.Frame(F.HsFinish, res.Value.fin))) { ConnectFailed("Connection lost"); return; }
        _connecting = false;
        Establish(link, res.Value.keys);
    }

    void ConnectFailed(string msg)
    {
        _connecting = false;
        _fails++;
        _nextAttempt = Now() + Math.Min(60_000, 4_000L * _fails);
        SetPhase(_fails >= 3 ? "CONNECTION FAILED" : "SEARCHING", _fails >= 3 ? msg : msg + " – retrying");
    }

    void Establish(BleLink link, SessionKeys keys)
    {
        _session?.Dispose();
        _session = link;
        _chan = new SecureChannel(keys);
        _authenticated.Add(link.Id);
        _fails = 0; _nextAttempt = 0;
        S.Trust!.LastConnected = Now(); S.SaveTrust();
        MarkSeen();
        if (_pendingNotice != null) { var n = _pendingNotice; _pendingNotice = null; _ = ShareNotice(n); }
        _inbox = Channel.CreateUnbounded<byte[]>();
        _ = ConsumeInbox(link, _inbox.Reader);
        SetPhase("CONNECTED", "End-to-end encrypted · hybrid post-quantum");
        _ = OnSessionUp();
    }

    async Task ConsumeInbox(BleLink link, ChannelReader<byte[]> reader)
    {
        try
        {
            await foreach (var m in reader.ReadAllAsync())
            {
                if (m.Length == 0) continue;
                try { await HandleApp(m[0], m[1..]); } catch { /* malformed message from partner: ignore */ }
            }
        }
        catch { }
    }

    async Task<bool> SendApp(byte type, byte[]? payload = null)
    {
        var link = _session; var ch = _chan;
        if (link == null || ch == null) return false;
        await _secLock.WaitAsync();
        try
        {
            if (link.Closed) return false;
            return await link.SendAsync(Ids.Frame(F.Secure, ch.Seal(Prims.Cat(new[] { type }, payload ?? Array.Empty<byte>()))));
        }
        finally { _secLock.Release(); }
    }

    // =============================================================== periodic

    long _lastStatus;
    void Tick()
    {
        var now = Now();
        foreach (var k in Nearby.Where(kv => now - kv.Value.LastSeen > 12_000).Select(kv => kv.Key).ToList()) { Nearby.Remove(k); Notify(); }
        if (Phase == "DEVICE FOUND" && now - _partnerSeen > 15_000 && !_connecting) SetPhase("SEARCHING", "Watch out of range");
        if (Phase == "CONNECTION FAILED" && now > _nextAttempt) SetPhase("SEARCHING", "Still looking…");
        if (!_scanner.Running && ScannerOk) { try { _scanner.Start(); } catch { } }
        MaybeConnect();
        if (Connected && now - _lastStatus > 15_000)
        {
            _lastStatus = now;
            MarkSeen();
            _ = SendStatus();
            _ = RetryPending();
        }
        FetchProfiles();
    }

    // =============================================================== messaging

    async Task OnSessionUp()
    {
        await SendProfile();
        await SendStatus();
        await RetryPending(force: true);
    }

    public async Task SendProfile() { if (Connected) await SendApp(A.Profile, new Out().Str(S.Profile.Name).Bytes(S.Profile.Avatar).Str(S.Profile.Bio).Build()); }

    Task<bool> SendStatus()
    {
        var bat = SystemInformation.PowerStatus.BatteryLifePercent;
        sbyte b = bat is >= 0 and <= 1 && SystemInformation.PowerStatus.BatteryChargeStatus != BatteryChargeStatus.NoSystemBattery ? (sbyte)(bat * 100) : (sbyte)-1;
        return SendApp(A.Status, new Out().B((byte)b).I(int.MinValue).B(0).Build());
    }

    async Task RetryPending(bool force = false)
    {
        List<Msg> pending;
        lock (S.Messages) pending = S.Messages.Where(m => m.Outgoing && m.Status is MsgStatus.Pending or MsgStatus.Transferring).ToList();
        foreach (var m in pending)
        {
            if (m.IsImage) { _ = SendImageTransfer(m); continue; }
            if (!force && _lastAttempt.TryGetValue(m.Id, out var t) && Now() - t < 12_000) continue;
            await TransmitText(m);
        }
    }

    async Task TransmitText(Msg m)
    {
        _lastAttempt[m.Id] = Now();
        await SendApp(A.Text, new Out().Raw(Ids.FromStr(m.Id)).L(m.Seq).L(m.SentAt).Str(m.Text).Build());
    }

    public async Task SendText(string text)
    {
        text = text.Trim();
        if (text.Length == 0 || S.Trust == null) return;
        if (text.Length > 1000) text = text[..1000];
        var now = Now();
        var m = new Msg { Id = Ids.ToStr(Ids.NewId()), Outgoing = true, Text = text, Seq = S.NextSeq(), SentAt = now, LocalTime = now, Status = MsgStatus.Pending };
        lock (S.Messages) S.Messages.Add(m);
        S.SaveMessages(); Notify();
        if (Connected) await TransmitText(m);
    }

    public void SendImage(byte[] jpeg, int w, int h)
    {
        if (S.Trust == null) return;
        var now = Now();
        var m = new Msg { Id = Ids.ToStr(Ids.NewId()), Outgoing = true, IsImage = true, Seq = S.NextSeq(), SentAt = now, LocalTime = now, Status = MsgStatus.Pending, Width = w, Height = h };
        S.SaveImage(m.Id, jpeg);
        lock (S.Messages) S.Messages.Add(m);
        S.SaveMessages(); Notify();
        if (Connected) _ = SendImageTransfer(m);
    }

    async Task HandleApp(byte type, byte[] p)
    {
        switch (type)
        {
            case A.Text:
            {
                var r = new In(p);
                var id = Ids.ToStr(r.Raw(16)); var seq = r.L(); var sentAt = r.L(); var text = r.Str(4000);
                if (text.Length > 1000) text = text[..1000];
                bool isNew;
                Msg m = new() { Id = id, Outgoing = false, Text = text, Seq = seq, SentAt = sentAt, LocalTime = Now(), Status = MsgStatus.Received };
                lock (S.Messages) { isNew = S.Messages.All(x => x.Id != id); if (isNew) S.Messages.Add(m); }
                await SendApp(A.Ack, Ids.FromStr(id));
                if (isNew) { S.SaveMessages(); Notify(); Incoming?.Invoke(m); }
                break;
            }
            case A.Ack:
            {
                var id = Ids.ToStr(new In(p).Raw(16));
                lock (S.Messages) foreach (var m in S.Messages.Where(x => x.Id == id && x.Outgoing)) { m.Status = MsgStatus.Delivered; m.Progress = 100; }
                _lastAttempt.Remove(id);
                S.SaveMessages(); Notify();
                break;
            }
            case A.Profile:
            {
                var r = new In(p);
                var name = Clean(r.Str(200)); var avatar = r.Bytes(60_000);
                var bio = r.HasMore ? r.Str(600).Trim() : null;
                if (S.Trust != null) { if (name != null) S.Trust.PartnerName = name; S.Trust.PartnerAvatar = avatar; if (bio != null) S.Trust.PartnerBio = bio.Length > 120 ? bio[..120] : bio; S.SaveTrust(); }
                Notify();
                break;
            }
            case A.Status:
            {
                var r = new In(p); int bat = (sbyte)r.B(); int rssi = r.I();
                if (r.HasMore) PartnerPhone = (r.B() & 1) == 1;
                RemoteBattery = bat is >= 0 and <= 100 ? bat : null;
                if (rssi is >= -127 and <= 0) PartnerRssi = PartnerRssi == null ? rssi : PartnerRssi + 0.25 * (rssi - PartnerRssi);
                Notify();
                break;
            }
            case A.ImgStart: await OnImgStart(p); break;
            case A.ImgChunk: await OnImgChunk(p); break;
            case A.ImgStatus: { var r = new In(p); var id = Ids.ToStr(r.Raw(16)); var next = r.I(); if (_outgoing.TryGetValue(id, out var ch)) ch.Writer.TryWrite(next); break; }
            case A.ImgFail: { var id = Ids.ToStr(new In(p).Raw(16)); if (_outgoing.TryGetValue(id, out var ch)) ch.Writer.TryWrite(-2); break; }
            case A.Unpair:
                LocalUnpair();
                SetPhase("NOT PAIRED", "Your watch removed the pairing");
                break;
            default:
                HandleExtra(type, p);
                break;
        }
    }

    // ---------------- images (same protocol as ImageTransfer.kt) ----------------
    const int Chunk = 3000, Window = 4;
    readonly Dictionary<string, Channel<int>> _outgoing = new();
    readonly Dictionary<string, bool> _incomingAudio = new();
    readonly Dictionary<string, (int size, int chunk, byte[] sha, int total, MemoryStream buf, int next)> _incoming = new();
    bool _imgBusy;

    async Task SendImageTransfer(Msg m)
    {
        if (_imgBusy || _outgoing.ContainsKey(m.Id)) return;
        var bytes = S.LoadImage(m.Id);
        if (bytes == null) { m.Status = MsgStatus.Failed; S.SaveMessages(); Notify(); return; }
        _imgBusy = true;
        var ch = Channel.CreateUnbounded<int>();
        _outgoing[m.Id] = ch;
        try
        {
            int total = (bytes.Length + Chunk - 1) / Chunk;
            m.Status = MsgStatus.Transferring; Notify();
            var start = new Out().Raw(Ids.FromStr(m.Id)).L(m.Seq).L(m.SentAt).I(bytes.Length).I(Chunk).Raw(Prims.Sha256(bytes)).I(m.Width).I(m.Height).B((byte)(m.IsAudio ? 1 : 0)).I(m.DurationMs).Build();
            if (!await SendApp(A.ImgStart, start)) return;
            int acked = await Next(ch, 10_000);
            if (acked < 0) { if (acked == -2) m.Status = MsgStatus.Failed; return; }
            int sent = acked, timeouts = 0;
            while (acked < total)
            {
                while (sent < total && sent - acked < Window)
                {
                    int from = sent * Chunk;
                    var data = bytes[from..Math.Min(bytes.Length, from + Chunk)];
                    if (!await SendApp(A.ImgChunk, new Out().Raw(Ids.FromStr(m.Id)).I(sent).Bytes(data).Build())) return;
                    sent++;
                }
                int ev = await Next(ch, 8000);
                if (ev == -1) { if (++timeouts > 3) return; sent = acked; }
                else if (ev == -2) { m.Status = MsgStatus.Failed; return; }
                else { acked = Math.Max(acked, ev); timeouts = 0; }
                m.Progress = acked * 100 / total; Notify();
            }
        }
        finally
        {
            _outgoing.Remove(m.Id); _imgBusy = false;
            if (m.Status == MsgStatus.Transferring) m.Status = MsgStatus.Pending;   // retried after ACK timeout / reconnect
            S.SaveMessages(); Notify();
        }

        static async Task<int> Next(Channel<int> c, int ms)
        {
            using var cts = new CancellationTokenSource(ms);
            try { return await c.Reader.ReadAsync(cts.Token); } catch { return -1; }
        }
    }

    async Task OnImgStart(byte[] p)
    {
        var r = new In(p);
        var idB = r.Raw(16); var id = Ids.ToStr(idB);
        long seq = r.L(), sentAt = r.L(); int size = r.I(), chunk = r.I(); var sha = r.Raw(32); int w = r.I(), h = r.I();
        bool isAudio = r.HasMore && r.B() == 1; int dur = r.HasMore ? r.I() : 0;
        _incomingAudio[id] = isAudio;
        if (size <= 0 || size > 400_000 || chunk < 256 || chunk > 8192) { await SendApp(A.ImgFail, new Out().Raw(idB).Str("rejected").Build()); return; }
        int total = (size + chunk - 1) / chunk;
        Msg? existing; lock (S.Messages) existing = S.Messages.FirstOrDefault(x => x.Id == id);
        if (existing?.Status == MsgStatus.Received)
        {
            await SendApp(A.ImgStatus, new Out().Raw(idB).I(total).Build());
            await SendApp(A.Ack, idB); return;
        }
        if (!_incoming.TryGetValue(id, out var inc) || inc.size != size || !inc.sha.AsSpan().SequenceEqual(sha))
            _incoming[id] = inc = (size, chunk, sha, total, new MemoryStream(), 0);
        if (existing == null)
        {
            lock (S.Messages) S.Messages.Add(new Msg { Id = id, Outgoing = false, IsImage = true, IsAudio = isAudio, DurationMs = dur, Seq = seq, SentAt = sentAt, LocalTime = Now(), Status = MsgStatus.Transferring, Width = w, Height = h });
            S.SaveMessages(); Notify();
        }
        await SendApp(A.ImgStatus, new Out().Raw(idB).I(inc.next).Build());
    }

    async Task OnImgChunk(byte[] p)
    {
        var r = new In(p);
        var idB = r.Raw(16); var id = Ids.ToStr(idB); int idx = r.I(); var data = r.Bytes(9000);
        if (data == null || !_incoming.TryGetValue(id, out var inc)) return;
        int expected = idx == inc.total - 1 ? inc.size - idx * inc.chunk : inc.chunk;
        if (idx == inc.next && data.Length == expected) { inc.buf.Write(data); inc.next++; _incoming[id] = inc; }
        await SendApp(A.ImgStatus, new Out().Raw(idB).I(inc.next).Build());
        Msg? m; lock (S.Messages) m = S.Messages.FirstOrDefault(x => x.Id == id);
        if (m != null) { m.Progress = inc.next * 100 / inc.total; Notify(); }
        if (inc.next < inc.total) return;
        _incoming.Remove(id);
        var bytes = inc.buf.ToArray();
        _incomingAudio.Remove(id, out var audio);
        bool ok = bytes.Length == inc.size && Prims.CtEquals(Prims.Sha256(bytes), inc.sha) &&
            (audio ? Adpcm.UnpackVoice(bytes) != null : bytes.Length > 3 && bytes[0] == 0xFF && bytes[1] == 0xD8);
        if (ok && audio && m != null) m.DurationMs = Adpcm.DurationMs(bytes);
        if (!ok)
        {
            await SendApp(A.ImgFail, new Out().Raw(idB).Str("validation").Build());
            if (m != null) m.Status = MsgStatus.Failed;
            S.SaveMessages(); Notify(); return;
        }
        S.SaveImage(id, bytes);
        if (m != null) { m.Status = MsgStatus.Received; m.Progress = 100; }
        S.SaveMessages(); Notify();
        await SendApp(A.Ack, idB);
        if (m != null) Incoming?.Invoke(m);
    }

    // =============================================================== pairing (PC = initiator)

    public async void StartPair(int discoveryId)
    {
        if (S.Trust != null) { PairFail("Unpair your current watch first."); return; }
        if (!Nearby.TryGetValue(discoveryId, out var n)) { PairFail("That watch is no longer nearby."); return; }
        if (Pair is PairPhase.Connecting or PairPhase.Waiting or PairPhase.Verify) return;
        PairName = n.Name ?? "Watch"; PairCode = ""; PairMyConfirmed = false; _peerConfirmSig = null;
        Pair = PairPhase.Connecting; PairNote = "Connecting…"; Notify();
        var link = await Open(n.Address, n.AddressType, 15);
        if (link == null) { PairFail("Could not reach the watch. Keep it on the Nearby screen and close by."); return; }
        _pairLink = link;
        _pi = new PairingInitiator(S);
        if (!await link.SendAsync(Ids.Frame(F.PairCommit, new Out().I(_myDiscoveryId).Raw(_pi.Commit).Str(S.Profile.Name).Build())))
        { PairFail("Connection lost."); return; }
        Pair = PairPhase.Waiting; PairNote = $"Accept the request on {PairName}…"; Notify();
        var me = link;
        _ = Task.Delay(120_000).ContinueWith(_ => _ui.Post(__ => { if (_pairLink == me && Pair is PairPhase.Waiting or PairPhase.Verify) PairAbort("Pairing timed out.", "timeout"); }, null));
    }

    async void OnPairFrame(BleLink l, byte[] f)
    {
        if (l != _pairLink || _pi == null) return;
        try
        {
            switch (f[0])
            {
                case F.PairAbort:
                    var why = new In(f, 1).Str(200);
                    PairFail(why == "collision" ? "Both sides pressed pair at once. Try again." : $"{PairName} cancelled ({why}).");
                    break;
                case F.PairResponse:
                {
                    var r = new In(f, 1);
                    var eph = r.Raw(32); var id = r.Raw(32); var nn = r.Raw(16); var kem = r.Raw(MlKem.PublicKeySize);
                    var name = Clean(r.Str(80)); if (name != null) PairName = name;
                    if (!_pi.OnResponse(eph, id, nn, kem)) { PairAbort("Key exchange failed.", "key exchange failed"); return; }
                    if (!await l.SendAsync(Ids.Frame(F.PairReveal, new Out().Raw(_pi.EphPub).Raw(_pi.MyId).Raw(_pi.Nonce).Raw(_pi.KemCiphertext!).Build())))
                    { PairFail("Connection lost."); return; }
                    PairCode = _pi.SasCode(); Pair = PairPhase.Verify; PairNote = $"Check {PairName} shows the same code."; Notify();
                    break;
                }
                case F.PairConfirm:
                {
                    var sig = f[1..];
                    if (Pair != PairPhase.Verify || !_pi.VerifyPeerConfirmation(sig))
                    {
                        Report("PAIRING TAMPER", "Pairing confirmation signature was invalid", l);
                        PairAbort("Watch confirmation invalid. Pairing stopped.", "bad confirmation");
                        return;
                    }
                    _peerConfirmSig = sig;
                    PairNote = PairMyConfirmed ? "Finishing…" : $"{PairName} confirmed ✓"; Notify();
                    await CheckPairComplete();
                    break;
                }
            }
        }
        catch { PairAbort("Invalid pairing data received.", "invalid data"); }
    }

    public async void ConfirmPair(bool match)
    {
        if (Pair != PairPhase.Verify || _pi == null || _pairLink == null || PairMyConfirmed) return;
        if (!match)
        {
            Report("CODE MISMATCH", "You reported that the verification codes differed", _pairLink);
            PairAbort("Codes did not match. Pairing cancelled for your safety.", "codes did not match");
            return;
        }
        PairMyConfirmed = true; PairNote = $"Waiting for {PairName} to confirm…"; Notify();
        if (!await _pairLink.SendAsync(Ids.Frame(F.PairConfirm, _pi.MyConfirmation()))) { PairFail("Connection lost."); return; }
        await CheckPairComplete();
    }

    async Task CheckPairComplete()
    {
        if (!PairMyConfirmed || _peerConfirmSig == null || _pi == null || _pairLink == null) return;
        S.SetTrust(new TrustData { PartnerId = _pi.PeerId!, Psk = _pi.DerivePsk(), PairedAt = Now(), PartnerName = PairName });
        _pi.WipeAll(); _pi = null;
        var link = _pairLink; _pairLink = null;
        _tokenWindow = -1;
        Pair = PairPhase.Success; PairNote = $"Paired with {PairName}"; Notify();
        await Handshake(link);
    }

    public void CancelPair() => PairAbort(null, "cancelled");
    public void DismissPair() { if (Pair is PairPhase.Success or PairPhase.Failed) { Pair = PairPhase.Idle; Notify(); } }

    async void PairAbort(string? msg, string reason)
    {
        var l = _pairLink;
        _pairLink = null; _pi?.WipeAll(); _pi = null;
        Pair = msg == null ? PairPhase.Idle : PairPhase.Failed; PairNote = msg ?? ""; Notify();
        if (l != null) { await l.SendAsync(Ids.Frame(F.PairAbort, new Out().Str(reason).Build())); await Task.Delay(300); l.Dispose(); }
    }

    void PairFail(string msg)
    {
        _pairLink?.Dispose(); _pairLink = null; _pi?.WipeAll(); _pi = null;
        Log("pair failed: " + msg);
        Pair = PairPhase.Failed; PairNote = msg; Notify();
    }

    // =============================================================== actions

    public async void Refresh()
    {
        if (S.Trust == null) { SetPhase("NOT PAIRED", "Pair with a watch first"); return; }
        SetPhase("REFRESHING", "Refreshing connection…");
        if (Connected)
        {
            if (await SendStatus()) { await Task.Delay(400); SetPhase("CONNECTED", "Connection healthy"); return; }
            _session?.Dispose();
        }
        _fails = 0; _nextAttempt = 0;
        _scanner.Stop(); await Task.Delay(300);
        try { _scanner.Start(); } catch { }
        SetPhase("SEARCHING", "Looking for your watch… (keep WristRadar installed and Bluetooth on)");
        MaybeConnect();
    }

    public async void Unpair()
    {
        if (Connected) { var t = SendApp(A.Unpair); await Task.WhenAny(t, Task.Delay(3000)); }
        LocalUnpair();
        SetPhase("NOT PAIRED", "Unpaired");
    }

    void LocalUnpair()
    {
        _session?.Dispose(); _session = null;
        S.ClearPartner();
        _partnerAddr = null; PartnerRssi = null; RemoteBattery = null; _tokens.Clear(); _tokenWindow = -1;
        Notify();
    }

    public void SaveProfile(string? name, byte[]? avatarJpeg, bool setAvatar)
    {
        if (name != null) { var c = Clean(name); if (c != null) S.Profile.Name = c; }
        if (setAvatar) S.Profile.Avatar = avatarJpeg;
        S.SaveProfile(); Notify();
        _ = SendProfile();
    }

    // =============================================================== blackbox

    void Report(string type, string detail, BleLink? l)
    {
        Log("BLACKBOX " + type);
        var dev = l?.DeviceName ?? "UNKNOWN DEVICE";
        lock (S.SecurityLog)
        {
            S.SecurityLog.Insert(0, new SecurityEvent { Time = Now(), Type = type, Detail = detail, Device = dev + (l != null ? $" · {l.Address:X12}" : "") });
            if (S.SecurityLog.Count > 500) S.SecurityLog.RemoveAt(S.SecurityLog.Count - 1);
        }
        S.SaveSecurityLog();
        RaiseWarning(type, detail, dev, fromPartner: false);
    }

    void RaiseAlert(Alert a)
    {
        if (_alert != null || DateTime.Now - _lastAlert < TimeSpan.FromSeconds(20)) return;
        _alert = a; _lastAlert = DateTime.Now;
        AlertRaised?.Invoke(a);
    }

    public void AlertDismissed() => _alert = null;
}
