using System.Buffers.Binary;
using NAudio.Wave;

namespace WristRadarPC.Core;

/// <summary>IMA ADPCM - identical to Adpcm.kt on the watch (verified by --selftest vector).</summary>
public static class Adpcm
{
    public const int SampleRate = 8000, CallFrame = 480;
    static readonly int[] Index = { -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8 };
    static readonly int[] Step = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767 };

    public sealed class State { public int Predictor; public int Idx; }

    public static byte[] EncodeBlock(short[] pcm, int count, State st)
    {
        int n = count & ~1;
        var o = new byte[3 + n / 2];
        o[0] = (byte)(st.Predictor >> 8); o[1] = (byte)st.Predictor; o[2] = (byte)st.Idx;
        for (int i = 0; i < n; i += 2)
        {
            int lo = Enc(pcm[i], st), hi = Enc(pcm[i + 1], st);
            o[3 + i / 2] = (byte)((hi << 4) | lo);
        }
        return o;
    }

    static int Enc(int sample, State st)
    {
        int diff = sample - st.Predictor, nib = 0;
        if (diff < 0) { nib = 8; diff = -diff; }
        int step = Step[st.Idx], vp = step >> 3;
        if (diff >= step) { nib |= 4; diff -= step; vp += step; }
        step >>= 1;
        if (diff >= step) { nib |= 2; diff -= step; vp += step; }
        step >>= 1;
        if (diff >= step) { nib |= 1; vp += step; }
        st.Predictor = Math.Clamp((nib & 8) != 0 ? st.Predictor - vp : st.Predictor + vp, -32768, 32767);
        st.Idx = Math.Clamp(st.Idx + Index[nib], 0, 88);
        return nib;
    }

    public static short[] DecodeBlock(byte[] b, int off, int len)
    {
        if (len < 3) return Array.Empty<short>();
        int pred = (short)((b[off] << 8) | b[off + 1]);
        int idx = Math.Clamp((int)b[off + 2], 0, 88);
        var o = new short[(len - 3) * 2];
        int k = 0;
        for (int i = off + 3; i < off + len; i++)
        {
            foreach (int nib in new[] { b[i] & 0x0f, b[i] >> 4 })
            {
                int step = Step[idx], d = step >> 3;
                if ((nib & 4) != 0) d += step;
                if ((nib & 2) != 0) d += step >> 1;
                if ((nib & 1) != 0) d += step >> 2;
                pred = Math.Clamp((nib & 8) != 0 ? pred - d : pred + d, -32768, 32767);
                idx = Math.Clamp(idx + Index[nib], 0, 88);
                o[k++] = (short)pred;
            }
        }
        return o;
    }

    static readonly byte[] Magic = "WRV1"u8.ToArray();

    public static byte[] PackVoice(short[] pcm, int count)
    {
        var block = EncodeBlock(pcm, count, new State());
        var r = new byte[12 + block.Length];
        Magic.CopyTo(r, 0);
        BinaryPrimitives.WriteInt32BigEndian(r.AsSpan(4), SampleRate);
        BinaryPrimitives.WriteInt32BigEndian(r.AsSpan(8), count & ~1);
        block.CopyTo(r, 12);
        return r;
    }

    public static short[]? UnpackVoice(byte[] b)
    {
        if (b.Length < 15 || !b.AsSpan(0, 4).SequenceEqual(Magic)) return null;
        int rate = BinaryPrimitives.ReadInt32BigEndian(b.AsSpan(4)), samples = BinaryPrimitives.ReadInt32BigEndian(b.AsSpan(8));
        if (rate != SampleRate || samples < 0 || samples > SampleRate * 120 || 3 + samples / 2 != b.Length - 12) return null;
        return DecodeBlock(b, 12, b.Length - 12);
    }

    public static int DurationMs(byte[] v) => v.Length < 12 ? 0 : (int)(BinaryPrimitives.ReadInt32BigEndian(v.AsSpan(8)) * 1000L / SampleRate);

    public static byte[] ToBytes(short[] s) { var b = new byte[s.Length * 2]; Buffer.BlockCopy(s, 0, b, 0, b.Length); return b; }
}

/// <summary>Microphone capture in 60 ms frames of 8 kHz mono 16-bit.</summary>
public sealed class Mic : IDisposable
{
    readonly WaveInEvent _in = new() { WaveFormat = new WaveFormat(Adpcm.SampleRate, 16, 1), BufferMilliseconds = 60, NumberOfBuffers = 4 };
    readonly List<short> _pending = new();
    public event Action<short[]>? Frame;
    public float Level { get; private set; }

    public Mic() { _in.DataAvailable += OnData; }
    public bool Start() { try { _in.StartRecording(); return true; } catch { return false; } }

    void OnData(object? s, WaveInEventArgs e)
    {
        for (int i = 0; i + 1 < e.BytesRecorded; i += 2) _pending.Add(BitConverter.ToInt16(e.Buffer, i));
        while (_pending.Count >= Adpcm.CallFrame)
        {
            var f = _pending.GetRange(0, Adpcm.CallFrame).ToArray();
            _pending.RemoveRange(0, Adpcm.CallFrame);
            Level = f.Max(x => Math.Abs((int)x)) / 32768f;
            Frame?.Invoke(f);
        }
    }

    public void Dispose() { try { _in.StopRecording(); } catch { } _in.Dispose(); }
}

/// <summary>Speaker for live call audio (drops old audio instead of building latency).</summary>
public sealed class Speaker : IDisposable
{
    readonly BufferedWaveProvider _buf = new(new WaveFormat(Adpcm.SampleRate, 16, 1)) { DiscardOnBufferOverflow = true, BufferDuration = TimeSpan.FromMilliseconds(600) };
    readonly WaveOutEvent _out = new() { DesiredLatency = 150 };
    public Speaker() { _out.Init(_buf); _out.Play(); }
    public void Write(short[] pcm) { var b = Adpcm.ToBytes(pcm); _buf.AddSamples(b, 0, b.Length); }
    public void Dispose() { try { _out.Stop(); } catch { } _out.Dispose(); }
}

/// <summary>Plays a stored voice message.</summary>
public static class VoicePlayback
{
    static WaveOutEvent? _out;
    public static string? PlayingId { get; private set; }
    public static event Action? Changed;

    public static void Toggle(string id, byte[]? voice)
    {
        if (PlayingId == id) { Stop(); return; }
        Stop();
        var pcm = voice == null ? null : Adpcm.UnpackVoice(voice);
        if (pcm == null || pcm.Length == 0) return;
        var src = new RawSourceWaveStream(new MemoryStream(Adpcm.ToBytes(pcm)), new WaveFormat(Adpcm.SampleRate, 16, 1));
        _out = new WaveOutEvent();
        _out.Init(src);
        _out.PlaybackStopped += (_, _) => { if (PlayingId == id) { PlayingId = null; Changed?.Invoke(); } };
        PlayingId = id;
        _out.Play();
        Changed?.Invoke();
    }

    public static void Stop()
    {
        PlayingId = null;
        try { _out?.Stop(); _out?.Dispose(); } catch { }
        _out = null;
        Changed?.Invoke();
    }
}
