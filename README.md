# WristRadar V1

A private, **fully offline** messenger for smartwatches, by **doktordestrukt (aka ellie)**.

Two Wear OS watches talk **directly over Bluetooth Low Energy**: no internet, Wi-Fi, phone, SIM,
account, server or cloud. It is built for and tested on the Samsung Galaxy Watch FE, and works on
a watch with no LTE. A Windows PC companion app speaks the same protocol.

> WristRadar is an independent personal project. It is not affiliated with or endorsed by Samsung
> or Google. It has **not** been independently security-audited.

## Features

- **Nearby discovery:** username, avatar, rough distance and signal strength
- **Verified pairing:** both people compare a 6-digit code, which blocks man-in-the-middle attacks
- **End-to-end encryption:** hybrid **post-quantum** key exchange (X25519 + ML-KEM-768), Ed25519
  identities, AES-256-GCM records, key rotation, and replay and tamper rejection
- **Messages:** text, pictures and voice messages, with delivery ticks, retries and history
- **Calls:** encrypted voice calls over Bluetooth, with an iPhone-style themed call screen
  (slide to answer, slide to hang up) and voicemail
- **Presence:** typing indicator, "Online" or "Last online 5 minutes ago", and whether each watch
  is connected to its phone
- **Background:** keeps running with the screen off (foreground service) and reconnects automatically
- **Security warnings:** both users are warned, with instructions, when authentication fails or
  records are tampered with or replayed. A device that is refused sees a BLACKBOX screen.
- **Themes:** 8 built-in themes plus a custom colour picker, with in-app banner notifications
- **PC companion:** a Windows app (C#/.NET, WinRT Bluetooth) with the same features

## Repository layout

| Folder | What it is |
|---|---|
| `WristRadar/` | The Wear OS app (Kotlin, Jetpack Compose for Wear OS, Room, Tink, Bouncy Castle) |
| `WristRadarPC/` | The Windows companion (C#, WinForms, WinRT Bluetooth LE, NAudio, Bouncy Castle) |
| `WristRadar/SECURITY.md` | The cryptographic design, threat model and limitations |
| `WristRadar/TESTING.md` | What has been tested, and how |

## Building

**Watch app.** Needs JDK 17 and the Android SDK (platform 36):

```bash
cd WristRadar && ./gradlew assembleDebug
```

**PC app.** Needs the .NET 10 SDK on Windows 10 2004 or newer:

```bash
cd WristRadarPC && dotnet build -c Release
```

**Sounds are not included.** The BLACKBOX screen's sound effects and music are not part of this
repository. Before building, add your own audio files with these names:
`glitch1.mp3`, `glitch2.mp3`, `glitch3.mp3` and `hacker_music.mp3`. They go in
`WristRadar/app/src/main/res/raw/` for the watch app and in `WristRadarPC/Assets/` for the PC app.

Release signing keys are not included. A release build without `keystore.properties` is unsigned.

## Licence

Source-available, **not open source**. You may read, study, build and use it, but you may not
re-publish, redistribute, modify publicly or sell it. See [`LICENSE`](LICENSE).
Third-party components keep their own licences, listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
