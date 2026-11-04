using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Security.Cryptography;
using WristRadarPC.Crypto;

namespace WristRadarPC.Ble;

/// <summary>
/// GATT client link to a watch's WristRadar service: writes go to RX (with response), the watch
/// notifies on TX. Same fragmentation as Link.kt: header byte 0x80=first, 0x40=last.
/// </summary>
public sealed class BleLink : IDisposable
{
    static int _counter;
    public int Id { get; } = Interlocked.Increment(ref _counter);
    public ulong Address { get; }
    public string? DeviceName => _dev?.Name is { Length: > 0 } n ? n : null;
    public bool Closed { get; private set; }
    public event Action<BleLink, byte[]>? Frame;
    public event Action<BleLink>? ClosedEvent;

    BluetoothLEDevice? _dev;
    GattSession? _session;
    GattCharacteristic? _rx, _tx;
    GattDeviceService? _svc;
    readonly SemaphoreSlim _sendLock = new(1, 1);
    MemoryStream? _reasm;

    BleLink(ulong addr) { Address = addr; }

    public static async Task<BleLink?> ConnectAsync(ulong address, BluetoothAddressType type, TimeSpan timeout)
    {
        var link = new BleLink(address);
        using var cts = new CancellationTokenSource(timeout);
        try
        {
            var task = link.OpenAsync(type);
            var done = await Task.WhenAny(task, Task.Delay(timeout, cts.Token));
            if (done != task) { Core.Engine.Log("connect timeout"); link.Dispose(); return null; }
            if (!await task) { Core.Engine.Log("connect failed at step " + link._step); link.Dispose(); return null; }
            cts.Cancel();
            return link;
        }
        catch (Exception e) { Core.Engine.Log("connect exception at " + link._step + ": " + e.GetType().Name + " " + e.Message); link.Dispose(); return null; }
    }

    string _step = "init";
    async Task<bool> OpenAsync(BluetoothAddressType type)
    {
        _step = "device";
        _dev = await BluetoothLEDevice.FromBluetoothAddressAsync(Address, type);
        if (_dev == null) return false;
        _session = await GattSession.FromDeviceIdAsync(_dev.BluetoothDeviceId);
        _session.MaintainConnection = true;
        _step = "services";
        var svcs = await _dev.GetGattServicesForUuidAsync(Advert.ServiceUuid, BluetoothCacheMode.Uncached);
        _step = "services:" + svcs.Status + "/" + svcs.Services.Count;
        if (svcs.Services.Count == 0)
        {
            var all = await _dev.GetGattServicesAsync(BluetoothCacheMode.Uncached);
            Core.Engine.Log("all services (" + all.Status + "): " + string.Join(", ", all.Services.Select(s => s.Uuid.ToString())) + " | name=" + _dev.Name + " conn=" + _dev.ConnectionStatus);
        }
        if (svcs.Status != GattCommunicationStatus.Success || svcs.Services.Count == 0) return false;
        _step = "characteristics";
        var svc = svcs.Services[0];
        _svc = svc;
        foreach (var extra in svcs.Services.Skip(1)) extra.Dispose();
        try { await svc.OpenAsync(GattSharingMode.SharedReadAndWrite); } catch { }
        var rx = await svc.GetCharacteristicsForUuidAsync(Advert.Rx, BluetoothCacheMode.Uncached);
        var tx = await svc.GetCharacteristicsForUuidAsync(Advert.Tx, BluetoothCacheMode.Uncached);
        _step = "characteristics:" + rx.Status + "/" + tx.Status;
        if (rx.Characteristics.Count == 0 || tx.Characteristics.Count == 0) return false;
        _rx = rx.Characteristics[0];
        _tx = tx.Characteristics[0];
        _tx.ValueChanged += OnValueChanged;
        _step = "cccd";
        var st = await _tx.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify);
        if (st != GattCommunicationStatus.Success) return false;
        _dev.ConnectionStatusChanged += (d, _) => { if (d.ConnectionStatus == BluetoothConnectionStatus.Disconnected) Dispose(); };
        return true;
    }

    int MaxFragment => Math.Max(20, (_session?.MaxPduSize ?? 23) - 3);

    public async Task<bool> SendAsync(byte[] frame)
    {
        await _sendLock.WaitAsync();
        try
        {
            int max = Math.Clamp(MaxFragment - 1, 19, 511); // ATT attribute values are capped at 512 bytes
            int off = 0; bool first = true;
            do
            {
                if (Closed || _rx == null) return false;
                int n = Math.Min(max, frame.Length - off);
                bool last = off + n >= frame.Length;
                var frag = new byte[n + 1];
                frag[0] = (byte)((first ? 0x80 : 0) | (last ? 0x40 : 0));
                Buffer.BlockCopy(frame, off, frag, 1, n);
                GattWriteResult? r = null;
                for (int attempt = 0; attempt < 3 && r?.Status != GattCommunicationStatus.Success; attempt++)
                {
                    try { r = await _rx.WriteValueWithResultAsync(CryptographicBuffer.CreateFromByteArray(frag), GattWriteOption.WriteWithResponse); }
                    catch { r = null; await Task.Delay(40 * (attempt + 1)); }
                }
                if (r?.Status != GattCommunicationStatus.Success) { Dispose(); return false; }
                off += n; first = false;
            } while (off < frame.Length);
            return true;
        }
        finally { _sendLock.Release(); }
    }

    void OnValueChanged(GattCharacteristic c, GattValueChangedEventArgs e)
    {
        CryptographicBuffer.CopyToByteArray(e.CharacteristicValue, out byte[] f);
        if (f == null || f.Length == 0) return;
        lock (this)
        {
            if ((f[0] & 0x80) != 0) _reasm = new MemoryStream();
            if (_reasm == null) return;
            _reasm.Write(f, 1, f.Length - 1);
            if (_reasm.Length > 64 * 1024) { _reasm = null; return; }
            if ((f[0] & 0x40) != 0) { var frame = _reasm.ToArray(); _reasm = null; Frame?.Invoke(this, frame); }
        }
    }

    public void Dispose()
    {
        if (Closed) return;
        Closed = true;
        try { if (_tx != null) _tx.ValueChanged -= OnValueChanged; } catch { }
        try { _svc?.Dispose(); } catch { }
        _svc = null;
        try { _session?.Dispose(); } catch { }
        try { _dev?.Dispose(); } catch { }
        _session = null; _dev = null; _rx = null; _tx = null;
        ClosedEvent?.Invoke(this);
    }
}

public sealed record AdvertSeen(ulong Address, BluetoothAddressType AddressType, short Rssi, bool Discoverable, int DiscoveryId, byte[] Token);

/// <summary>Active BLE scanning for the WristRadar service data in scan responses.</summary>
public sealed class Scanner
{
    readonly BluetoothLEAdvertisementWatcher _w = new() { ScanningMode = BluetoothLEScanningMode.Active };
    readonly byte[] _uuidLe = Advert.ServiceUuidLe();
    public event Action<AdvertSeen>? Seen;
    public bool Running => _w.Status == BluetoothLEAdvertisementWatcherStatus.Started;
    public BluetoothLEAdvertisementWatcherStatus Status => _w.Status;

    public Scanner() { _w.Received += OnReceived; }

    public void Start() { if (_w.Status != BluetoothLEAdvertisementWatcherStatus.Started) _w.Start(); }
    public void Stop() { try { _w.Stop(); } catch { } }

    void OnReceived(BluetoothLEAdvertisementWatcher w, BluetoothLEAdvertisementReceivedEventArgs a)
    {
        foreach (var sec in a.Advertisement.DataSections)
        {
            if (sec.DataType != 0x21) continue; // Service Data - 128-bit UUID
            CryptographicBuffer.CopyToByteArray(sec.Data, out byte[] d);
            if (d == null || d.Length < 16 + 13 || !d.AsSpan(0, 16).SequenceEqual(_uuidLe)) continue;
            var p = d.AsSpan(16, 13).ToArray();
            int id = (p[1] << 24) | (p[2] << 16) | (p[3] << 8) | p[4];
            Seen?.Invoke(new AdvertSeen(a.BluetoothAddress, a.BluetoothAddressType, a.RawSignalStrengthInDBm, (p[0] & 1) == 1, id, p[5..13]));
        }
    }
}
