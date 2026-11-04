using System.Threading.Channels;
using WristRadarPC.Crypto;

namespace WristRadarPC.Core;

public enum CallPhase { Idle, Outgoing, Incoming, Active, NoAnswer, Ended }

public sealed record Warning(string Title, string Detail, string Device, DateTime Time, bool FromPartner, int Repeats = 0);

/// <summary>Calls, voice messages, typing, presence and security notices (same protocol as the watch).</summary>
public sealed partial class Engine
{
    // ---------------- presence ----------------
    public bool PartnerTyping { get; private set; }
    public bool? PartnerPhone { get; private set; }
    CancellationTokenSource? _typingCts;
    long _lastTypingSent; bool _typingSentState;

    void OnPartnerTyping(bool typing)
    {
        _typingCts?.Cancel();
        PartnerTyping = typing; Notify();
        if (typing)
        {
            var cts = _typingCts = new CancellationTokenSource();
            _ = Task.Delay(15_000, cts.Token).ContinueWith(t => { if (!t.IsCanceled) _ui.Post(_ => { PartnerTyping = false; Notify(); }, null); });
        }
    }

    /// <summary>Called from the text box: sends "typing" at most every 4 s, and "stopped" once.</summary>
    public void NotifyTyping(bool typing)
    {
        if (!Connected) return;
        if (typing && _typingSentState && Now() - _lastTypingSent < 4000) return;
        if (!typing && !_typingSentState) return;
        _typingSentState = typing; _lastTypingSent = Now();
        _ = SendApp(A.Typing, new[] { (byte)(typing ? 1 : 0) });
    }

    void MarkSeen() { if (S.Trust != null) { S.Trust.PartnerLastSeen = Now(); S.SaveTrust(); } }

    // ---------------- security warnings (legit users) ----------------
    public event Action<Warning>? WarningRaised;
    Warning? _warning; string? _pendingNotice;
    public void WarningDismissed() => _warning = null;

    void RaiseWarning(string title, string detail, string device, bool fromPartner)
    {
        if (_warning != null) { _warning = _warning with { Repeats = _warning.Repeats + 1 }; return; }
        if (DateTime.Now - _lastAlert < TimeSpan.FromSeconds(20)) return;
        _lastAlert = DateTime.Now;
        _warning = new Warning(title, detail, device, DateTime.Now, fromPartner);
        WarningRaised?.Invoke(_warning);
        if (!fromPartner) _ = ShareNotice(title);
    }

    async Task ShareNotice(string title)
    {
        if (!Connected || !await SendApp(A.SecurityNotice, new Out().Str(title).Build())) _pendingNotice = title;
    }

    // ---------------- calls ----------------
    public CallPhase Call { get; private set; } = CallPhase.Idle;
    public string CallEndReason { get; private set; } = "";
    public DateTime CallSince { get; private set; }
    public bool Muted { get; set; }
    byte[]? _callId; CancellationTokenSource? _callTimeout;
    Mic? _mic; Speaker? _spk; int _callSeq;
    readonly Channel<byte[]> _callOut = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(3) { FullMode = BoundedChannelFullMode.DropOldest });
    bool _callSenderRunning;
    public event Action? CallChanged;
    void SetCall(CallPhase p, string reason = "") { Call = p; CallEndReason = reason; CallChanged?.Invoke(); Notify(); }

    public bool StartCall()
    {
        if (Call is CallPhase.Outgoing or CallPhase.Incoming or CallPhase.Active || !Connected) return false;
        _callId = Prims.Random(16);
        SetCall(CallPhase.Outgoing);
        _ = SendApp(A.CallInvite, _callId);
        ArmCallTimeout(30_000, () =>
        {
            if (_callId != null) _ = SendApp(A.CallEnd, new Out().Raw(_callId).Str("noanswer").Build());
            _callId = null; SetCall(CallPhase.NoAnswer);
        });
        return true;
    }

    public void AcceptCall()
    {
        if (Call != CallPhase.Incoming || _callId == null) return;
        _callTimeout?.Cancel();
        _ = SendApp(A.CallAccept, _callId);
        GoActive();
    }

    public void DeclineCall()
    {
        if (_callId == null) return;
        _callTimeout?.Cancel();
        _ = SendApp(A.CallDecline, new Out().Raw(_callId).Str("declined").Build());
        _callId = null; SetCall(CallPhase.Ended, "Declined");
    }

    public void HangUp()
    {
        _callTimeout?.Cancel();
        var was = Call;
        if (_callId != null) _ = SendApp(A.CallEnd, new Out().Raw(_callId).Str("hangup").Build());
        EndCall(was == CallPhase.Active ? "Call ended" : "Cancelled");
    }

    public void ResetCall() { if (Call is CallPhase.Ended or CallPhase.NoAnswer) SetCall(CallPhase.Idle); }

    void ArmCallTimeout(int ms, Action a)
    {
        _callTimeout?.Cancel();
        var cts = _callTimeout = new CancellationTokenSource();
        _ = Task.Delay(ms, cts.Token).ContinueWith(t => { if (!t.IsCanceled) _ui.Post(_ => a(), null); });
    }

    void EndCall(string reason)
    {
        _callId = null;
        StopCallAudio();
        SetCall(CallPhase.Ended, reason);
    }

    void GoActive()
    {
        Muted = false; CallSince = DateTime.Now;
        SetCall(CallPhase.Active);
        _spk = new Speaker();
        _mic = new Mic();
        var id = _callId!;
        var st = new Adpcm.State();
        _mic.Frame += f =>
        {
            if (Muted) Array.Clear(f);
            _callOut.Writer.TryWrite(new Out().Raw(id).I(_callSeq++).Raw(Adpcm.EncodeBlock(f, f.Length, st)).Build());
        };
        if (!_mic.Start()) Log("microphone unavailable");
        if (!_callSenderRunning) _ = CallSender();
    }

    async Task CallSender()
    {
        _callSenderRunning = true;
        try
        {
            await foreach (var pkt in _callOut.Reader.ReadAllAsync())
            {
                if (Call != CallPhase.Active) continue;
                await SendApp(A.CallAudio, pkt);
            }
        }
        finally { _callSenderRunning = false; }
    }

    void StopCallAudio()
    {
        _mic?.Dispose(); _mic = null;
        _spk?.Dispose(); _spk = null;
        while (_callOut.Reader.TryRead(out _)) { }
    }

    void OnCallSignal(byte type, byte[] p)
    {
        var r = new In(p);
        var id = r.Raw(16);
        bool mine = _callId != null && _callId.AsSpan().SequenceEqual(id);
        switch (type)
        {
            case A.CallInvite:
                if (Call is CallPhase.Outgoing or CallPhase.Incoming or CallPhase.Active)
                { _ = SendApp(A.CallDecline, new Out().Raw(id).Str("busy").Build()); return; }
                _callId = id;
                SetCall(CallPhase.Incoming);
                ArmCallTimeout(32_000, () => { _callId = null; SetCall(CallPhase.Ended, "Missed call"); });
                break;
            case A.CallAccept:
                if (mine && Call == CallPhase.Outgoing) { _callTimeout?.Cancel(); GoActive(); }
                break;
            case A.CallDecline:
                if (mine)
                {
                    var why = SafeStr(r);
                    _callTimeout?.Cancel(); _callId = null; StopCallAudio();
                    SetCall(why == "busy" ? CallPhase.Ended : CallPhase.NoAnswer, why == "busy" ? "Busy" : "");
                }
                break;
            case A.CallEnd:
                if (mine)
                {
                    var why = SafeStr(r);
                    _callTimeout?.Cancel();
                    if (why == "noanswer" && Call == CallPhase.Incoming) { _callId = null; SetCall(CallPhase.Ended, "Missed call"); }
                    else EndCall("Call ended");
                }
                break;
            case A.CallAudio:
                if (mine && Call == CallPhase.Active && p.Length > 20)
                {
                    r.I();
                    _spk?.Write(Adpcm.DecodeBlock(p, 20, p.Length - 20));
                }
                break;
        }

        static string SafeStr(In r) { try { return r.Str(40); } catch { return ""; } }
    }

    void OnLinkLostExtras()
    {
        MarkSeen();
        PartnerTyping = false;
        if (Call is CallPhase.Outgoing or CallPhase.Incoming or CallPhase.Active) { _callTimeout?.Cancel(); EndCall("Connection lost"); }
    }

    // ---------------- voice messages ----------------
    public void SendVoice(byte[] voice)
    {
        if (S.Trust == null) return;
        var now = Now();
        var m = new Msg
        {
            Id = Ids.ToStr(Ids.NewId()), Outgoing = true, IsImage = true, IsAudio = true, Seq = S.NextSeq(),
            SentAt = now, LocalTime = now, Status = MsgStatus.Pending, DurationMs = Adpcm.DurationMs(voice),
        };
        S.SaveImage(m.Id, voice);
        lock (S.Messages) S.Messages.Add(m);
        S.SaveMessages(); Notify();
        if (Connected) _ = SendImageTransfer(m);
    }

    /// <summary>Handles the newer message types. Returns false for unknown types.</summary>
    bool HandleExtra(byte type, byte[] p)
    {
        switch (type)
        {
            case A.Typing: OnPartnerTyping(p.Length > 0 && p[0] == 1); return true;
            case A.CallInvite or A.CallAccept or A.CallDecline or A.CallEnd or A.CallAudio: OnCallSignal(type, p); return true;
            case A.SecurityNotice:
            {
                string what; try { what = new In(p).Str(300); } catch { what = "Intrusion attempt"; }
                lock (S.SecurityLog) S.SecurityLog.Insert(0, new SecurityEvent { Time = Now(), Type = "PARTNER REPORTED", Detail = what, Device = "Partner's watch" });
                S.SaveSecurityLog();
                RaiseWarning("Your partner's watch detected an intrusion attempt", what, "Partner's watch", fromPartner: true);
                return true;
            }
        }
        return false;
    }
}
