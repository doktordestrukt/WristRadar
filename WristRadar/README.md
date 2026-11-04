# WRISTRADAR V1

A private, fully offline messenger for two Samsung Galaxy Watch FE watches (any Wear OS 4+ watch
with BLE advertising should work). The watches talk **directly over Bluetooth Low Energy**: no
internet, Wi-Fi, phone, SIM, account, server or cloud.

- Discover nearby WristRadar watches: username, avatar, rough distance and signal strength
- Pair once with a **6-digit verification code** that both people compare. This blocks man-in-the-middle attacks.
- End-to-end encrypted text and picture messages, with delivery ticks, retries and history
- Runs in the background (foreground service). Vibrating notifications with inline reply.
- Reconnects automatically. A **Refresh connection** button is available on both watches.
- Custom username and profile picture, synced over the encrypted link
- 8 built-in themes plus a fully custom theme. The default is black and purple.
- Pictures and profile pictures come from **your own gallery app** (Samsung Gallery on the watch), with a built-in fallback grid.
- Hybrid **post-quantum** key exchange: X25519 + ML-KEM-768
- **BLACKBOX** intrusion alert: an original laughing skull, glitch effects and hacker music, shown only on cryptographic evidence of unauthorised access. Events go to a local security log.

See [SECURITY.md](SECURITY.md) for the cryptographic design and its limits, and
[TESTING.md](TESTING.md) for what has been verified so far and what has not.

---

## 1. Ready-made APKs

| File | Use |
|---|---|
| `dist/WristRadar-V1-release.apk` | Minified build, signed with the project's release key (`wristradar-release.jks`). **Use this one.** |
| `dist/WristRadar-V1-debug.apk` | Debug build, signed with the local Android debug key. |

Install the **same variant on both watches**. The two variants have different signatures, so switching
variants on one watch needs `adb uninstall com.wristradar` first. Uninstalling deletes that watch's keys and
messages.

> **Back up `wristradar-release.jks` and `keystore.properties` privately.** Without them you cannot
> ship an update that installs over the existing app. Never commit them to a public repository.

## 2. Install on BOTH watches with ADB

You need `adb` (Android platform-tools). The watch and the computer must be on the same Wi-Fi network
**for installation only**. WristRadar itself never uses Wi-Fi.

On **each** watch:

1. **Settings → About watch → Software information** → tap **Software version** 5 times. This unlocks Developer options.
2. **Settings → Developer options** → turn on **ADB debugging** and **Wireless debugging**.
3. Open **Wireless debugging → Pair new device**. The watch shows an `IP:port` and a pairing code.

On the computer (run these for watch 1, then repeat for watch 2 with its own addresses):

```bash
adb pair <watch-ip>:<pairing-port>
```
(enter the pairing code shown on the watch)

```bash
adb connect <watch-ip>:<port>
```
(the address shown on the main *Wireless debugging* screen. It is a different port from the pairing one.)

```bash
adb devices
```

```bash
adb -s <watch-ip>:<port> install -r dist/WristRadar-V1-release.apk
```

Optionally, you can pre-grant the runtime permissions instead of tapping *Allow* on the watch:

```bash
adb -s <watch-ip>:<port> shell pm grant com.wristradar android.permission.BLUETOOTH_SCAN
```
```bash
adb -s <watch-ip>:<port> shell pm grant com.wristradar android.permission.BLUETOOTH_CONNECT
```
```bash
adb -s <watch-ip>:<port> shell pm grant com.wristradar android.permission.BLUETOOTH_ADVERTISE
```
```bash
adb -s <watch-ip>:<port> shell pm grant com.wristradar android.permission.POST_NOTIFICATIONS
```
```bash
adb -s <watch-ip>:<port> shell pm grant com.wristradar android.permission.READ_MEDIA_IMAGES
```

Turn **Wireless debugging off** afterwards. It drains the battery.

### Permissions WristRadar asks for

| Permission (watch wording) | Why |
|---|---|
| **Nearby devices** (BLUETOOTH_SCAN / CONNECT / ADVERTISE) | Required. Used to find, advertise to, and connect to the other watch. Scanning is declared `neverForLocation`. |
| **Notifications** | Message alerts and the ongoing "connected" status. |
| **Photos / images** (READ_MEDIA_IMAGES) | Only for the fallback photo grid. The gallery app needs no extra permission. |
| Full-screen alerts (USE_FULL_SCREEN_INTENT) | Lets a BLACKBOX alert open over other apps. Android may restrict this; it falls back to a normal alert. |
| Foreground service, boot, vibrate | Granted at install. They keep the link alive in the background and after a reboot. |

If you deny *Nearby devices*, the home screen shows **Grant permissions**.

## 3. Using it

1. Open WristRadar on both watches. Set a username: tap your avatar → **Edit username**.
2. On **both** watches tap **Connect to nearby watches**. Each watch lists the other.
3. Tap the other person → **Pair with this watch**. Either one or both of you can press it. The other watch shows a request: **Accept**.
4. Both watches show the same **6-digit code**. Compare them side by side. If they match, both tap **Codes match**.
5. Done. The pairing is remembered. From now on the watches find each other automatically whenever WristRadar
   is installed, Bluetooth is on and they are in range. The app does not need to be open.
6. **Chat**: keyboard button = system input (keyboard, voice or handwriting, whatever your watch offers). Picture button =
   choose a photo stored on the watch. Quick replies are at the bottom.
7. If the link seems stuck, press **Refresh connection** on either watch.
8. **Settings → Unpair device** removes the trust keys and the conversation. The partner is told if it is in range.
9. The picture button and *Change profile picture* open your gallery app straight away. Pick a photo and confirm it in the preview.
   If the watch has no gallery app, a grid of photos stored on the watch is shown instead.

### BLACKBOX intrusion alert

**Settings → Blackbox security** has sound, music and vibration toggles, the **Security log**, and a **Preview alert** button.
The preview is clearly labelled and is not logged.

The alert triggers **only** on cryptographic evidence:

- A device fails the identity/PSK check during the handshake.
- A device that advertises your partner's secret rolling ID cannot prove it is your partner.
- Encrypted data arrives without a completed handshake.
- A message fails its AES-GCM authentication tag, or is an authentic replay.
- Pairing data was altered (the reveal does not match its commitment, or the confirmation signature is invalid).
- You tap *Codes differ* during pairing.
- Stored data fails its Keystore integrity check.

Scanning, nearby watches, normal traffic and disconnects never trigger it.

When it fires: vibration, then **Glitch2**, then the **Hacker Mode music** loops until you dismiss the alert. Glitch3 plays on
dismiss, and Glitch1 plays when the security log opens. The screen shows the laughing skull with glitch effects,
*PLEASE DO NOT DECRYPT THIS*, *BLACKBOX // UNAUTHORISED ACCESS DETECTED*, the event, and the device's Bluetooth name or
**UNKNOWN DEVICE** with its (rotating) Bluetooth ID.

Alerts never overlap. Repeats within 20 s are logged and counted, not replayed. If WristRadar is in the background, a
full-screen or heads-up notification is shown instead. If the intruding device runs WristRadar, it is sent a *BLACKBOX*
reply, and its screen shows the same skull, glitch and music as **ACCESS DENIED**.

The **laughing sound effect** was not among the files provided. To add it, put it in `app/src/main/res/raw/` named
`laugh.mp3` (lower case), then rebuild. It plays together with Glitch2 automatically.

## 4. Building from source

Requirements: Android Studio (Ladybug or newer) **or** JDK 17 + Android SDK (platform 36, build-tools 36.0.0).

```bash
./gradlew assembleDebug
```
```bash
./gradlew assembleRelease
```
```bash
./gradlew testDebugUnitTest
```

Outputs are in `app/build/outputs/apk/`. Release signing reads `keystore.properties` from the project root. If
that file is missing, the release APK is built unsigned.

Pinned toolchain: AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · compileSdk/targetSdk 36 ·
minSdk 33 (Wear OS 4).

To change the version, edit `wrVersionCode`, `wrVersionName` and `wrReleaseLabel` at the top of
`app/build.gradle.kts`. The UI reads them from `BuildConfig`.

## 5. Architecture

```
ui/            Compose for Wear OS screens, theme, components (round-screen ScalingLazyColumn)
core/          RadarEngine (radio policy, links, reconnection), PairingController,
               Messenger (text/ACK/retry/profile/status), ImageTransfer (chunked, acked)
ble/           GattServerHost, ClientLink, Link (fragmentation), BleAdvertiser, BleScanner,
               AdvertPayload (rolling tokens), DistanceEstimator (RSSI → smoothed estimate)
crypto/        Crypto (Tink + JCA wrappers), KeystoreVault, IdentityManager (Ed25519), MlKem (BouncyCastle),
               PairingCrypto (commit + SAS + ML-KEM), SessionHandshake (hybrid SIGMA + PSK), SecureChannel
security/      Blackbox detector (threat types, cooldown, local log)
ui/blackbox/   Skull alert scene and bundled audio (res/raw: glitch1-3, hacker_music)
protocol/      Wire format (frame types, binary encoding)
data/          Room DB (messages), SettingsStore, TrustStore, ProfileStore, MessageRepository
notify/        Notifier (channels, message notification, vibration), ReplyReceiver
service/       WristRadarService (foreground, connectedDevice) + BootReceiver
```

**Radio model.** Both watches run a GATT server (RX write / TX notify characteristics) and advertise the
WristRadar service UUID. A scan-response field carries a discoverable flag, a per-session discovery ID and a
rolling 8-byte token that only the paired partner can recognise. Both watches scan with a hardware UUID filter,
so scanning keeps working with the screen off. For the paired link, the watch with the lower identity key
connects as GATT client. The other watch waits about 12 s before trying itself. If both links complete
anyway, both sides keep the same one, so duplicates close deterministically. While connected, scanning and
advertising stop to save battery. After the link drops, they run at high duty for 60 s and then at low power.

**Reliability.** Frames are fragmented to the negotiated MTU (up to 512 bytes), with per-fragment flow
control. Messages carry a UUID and a sequence number. The receiver stores them with an insert-ignore on the
UUID and always acknowledges. The sender retries unacknowledged messages every 12 s while connected and flushes
the queue on every reconnect. Pictures are resized to 480 px JPEG (typically 20–60 KB) and sent in 3 KB
chunks: go-back-N with a window of 4, resuming after reconnects, then checked with SHA-256 and a decode test.

## 6. Known limitations (honest list)

- **Not yet tested on real Galaxy Watch FE hardware.** Section 2 of [TESTING.md](TESTING.md) is the checklist for the
  two-watch test.
- BLE range is roughly 10–30 m in the open and much less through walls or bodies. Distance is a rough RSSI
  estimate, not GPS.
- Wear OS can still stop the background service, for example under heavy battery saver or low memory. It restarts
  (START_STICKY, plus after reboot), but messages wait in the sender's queue until the link is back.
- Picture transfer over BLE is slow by design: a few KB/s to tens of KB/s, so a 50 KB photo takes several seconds.
- The picture picker reads photos stored on the watch. If none are stored, the picker says so. Any installed
  gallery app is also offered.
- V1 supports exactly one partner. There are no groups.

## 7. New in this build

- **Voice calls**: the call button in the chat header. The call screen is themed like an iPhone call: pulsing avatar, *slide to answer*, decline, mute, and *slide to hang up*. Audio is 8 kHz IMA ADPCM, about 4 KB/s, sent through the same encrypted session. Expect phone-call quality with roughly a quarter to half a second of delay over Bluetooth.
- **Voicemail and voice messages**: if a call isn't answered, you get *Leave a voicemail*. The 🎤 button in the chat records a voice message of up to 30 s. Both are sent encrypted and play back in the chat.
- **Typing indicator**: shows "typing…" while the other person has the keyboard open on the watch, or is typing on the PC.
- **Online / last online**: "Online", or "Last online 5 minutes ago" (seconds, minutes, hours, days, months, years).
- **Phone connection**: shows whether each watch is connected to its phone. This is read locally from Wear OS and shared over the encrypted link.
- **Bio**: in your profile. It syncs live with your name and picture.
- **Zoomable pictures**: pinch, drag, double-tap, or turn the bezel.
- **In-app banner**: drops in from the top when a message arrives while you're elsewhere in the app. System notifications outside the app are drawn by Wear OS, so their style can't be changed.
- **Security**: on real evidence of a break-in, *both* of you get a themed warning with instructions. The device that tried to break in gets the skull and hacker music. The preview button has been removed.
