<p align="center">
  <img src="docs/images/banner.png" alt="WristRadar - private watch-to-watch messaging" width="100%">
</p>

<p align="center">
  <a href="https://github.com/doktordestrukt/WristRadar/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/doktordestrukt/WristRadar?style=for-the-badge&label=latest&color=b04cff&logo=wearos&logoColor=white"></a>
  <a href="https://github.com/doktordestrukt/WristRadar/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/doktordestrukt/WristRadar/total?style=for-the-badge&color=ff4fb8&logo=github&logoColor=white"></a>
  <img alt="Wear OS 4+" src="https://img.shields.io/badge/Wear%20OS-4%2B-4285F4?style=for-the-badge&logo=wearos&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
</p>

<p align="center">
  <img alt="No internet" src="https://img.shields.io/badge/internet-not%20needed-2ea44f?style=flat-square">
  <img alt="Post-quantum" src="https://img.shields.io/badge/encryption-post--quantum-8a2be2?style=flat-square">
  <img alt="ML-KEM-1024" src="https://img.shields.io/badge/ML--KEM-1024-c2185b?style=flat-square">
  <img alt="ML-DSA-87" src="https://img.shields.io/badge/ML--DSA-87-c2185b?style=flat-square">
  <img alt="AES-256-GCM" src="https://img.shields.io/badge/AES-256--GCM-6a1b9a?style=flat-square">
  <img alt="Tested on emulators" src="https://img.shields.io/badge/tested%20on-Wear%20OS%206%20emulators-ff9800?style=flat-square">
</p>

<p align="center">
  <a href="https://github.com/doktordestrukt/WristRadar/releases/latest"><img alt="Download the latest APK" src="https://img.shields.io/badge/%E2%AC%87%EF%B8%8F%20Download-latest%20APK-b04cff?style=for-the-badge"></a>
  <a href="WristRadar/README.md"><img alt="Install guide" src="https://img.shields.io/badge/%F0%9F%93%96%20Install-guide-ff4fb8?style=for-the-badge"></a>
  <a href="WristRadar/SECURITY.md"><img alt="Security design" src="https://img.shields.io/badge/%F0%9F%9B%A1%EF%B8%8F%20Security-design-6a1b9a?style=for-the-badge"></a>
</p>

> **This was just made for fun, so I could text my friend on his watch, lol.** 😄

A private, **fully offline** messenger for smartwatches, by **doktordestrukt (aka ellie)**.

Two Wear OS watches talk **directly over Bluetooth Low Energy**: messages never touch the internet,
a phone, a SIM, an account, a server or the cloud. Built for, and tested on, the Samsung Galaxy
Watch FE (no LTE needed). A Windows PC companion speaks the same protocol.

> WristRadar is an independent personal project. It is not affiliated with or endorsed by Samsung
> or Google. It has **not** been independently security-audited.

---

## 📸 Screenshots

<table align="center">
  <tr>
    <td align="center"><img src="docs/images/home.png" width="190"><br><sub><b>Home</b> · Dynamic Island</sub></td>
    <td align="center"><img src="docs/images/theme_nebula.png" width="190"><br><sub><b>Gradient glass</b> themes</sub></td>
    <td align="center"><img src="docs/images/effects.png" width="190"><br><sub><b>Message effects</b></sub></td>
    <td align="center"><img src="docs/images/games.png" width="190"><br><sub><b>Games</b></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/pin_intro.png" width="190"><br><sub><b>"We've launched pins!"</b></sub></td>
    <td align="center"><img src="docs/images/safety_code.png" width="190"><br><sub><b>Emoji safety code</b></sub></td>
    <td align="center"><img src="docs/images/appearance.png" width="190"><br><sub><b>Theme folders</b></sub></td>
    <td align="center"><img src="docs/images/intruder.png" width="190"><br><sub><b>BLACKBOX</b> intruder screen</sub></td>
  </tr>
</table>

<p align="center"><sub>Real screenshots from WristRadar running on Wear OS 6 emulators (test identities, no real chats).</sub></p>

---

## ✨ Features

<table>
  <tr>
    <td width="50%" valign="top">

**💬 Chat**
- Text, pictures (from the watch itself, never the cloud) and voice messages with sound waves
- Blue seen ticks, *Delivered / Read*, replies, reactions, edit, delete for both, pins, search
- Discord-style **bold**, *italic*, `code`, ||spoilers|| and big emojis
- 14 full-screen **message effects** - confetti, fireworks, lasers, rainbow, thunder…
- Keyboard opens on the first tap, right inside the app

**📞 Calls**
- Encrypted voice calls over Bluetooth, louder-call boost, mute shown to your friend

**🎮 Fun**
- Connect 4, Memory Match, Dice Duel, Rock Paper Scissors, Tic-Tac-Toe, Reaction Duel
- Draw & send, emoji blasts, tap code, streaks, mood, shared to-do list, hide & seek

</td>
    <td width="50%" valign="top">

**🎨 Looks**
- 50+ themes in folders: Liquid glass, Gradient glass, Soft glass, KK, Gradients, Dark
- The app icon changes to match your theme (round, launcher-safe)
- Tilt shimmer, tap ripples, night glass, Dynamic Island, haptic taps

**📡 Range**
- Max Range: Bluetooth LE Coded PHY (S2 → S8, up to ~4× normal reach) on both watches
- Watch-tuned distance + signal bars, one-tap calibration, walk-to-friend

**🔐 Security**
- App lock PIN, sealed in the watch's hardware Keystore
- Emoji safety code, BLACKBOX intruder screen, local security log
- Updates over Bluetooth or GitHub, signature-checked, chats kept

</td>
  </tr>
</table>

## 🛡️ Encryption at a glance

| Layer | What WristRadar uses |
|---|---|
| Key exchange | **X25519 + ML-KEM-1024** (hybrid, post-quantum) - fresh keys every connection |
| Identity | **Ed25519 + ML-DSA-87** - both signatures must verify |
| Key derivation | **HKDF-SHA-384**, transcripts hashed with **SHA-384** |
| Messages | **AES-256-GCM**, a **new key for every message** (hash ratchet), replay protection |
| Healing | Complete new key exchange every hour |
| Pairing | 6-digit code + ML-KEM-1024 + ML-DSA-87, safety-code check in person |
| On the watch | Keys, messages and PIN sealed with the hardware-backed Android Keystore |

Older watches fall back automatically to the strongest set they understand. Full details, the
threat model and the limits are in [`WristRadar/SECURITY.md`](WristRadar/SECURITY.md).

## 🚀 Version history

| Version | Highlights |
|---|---|
| **MK18.1 Horizon** | Range pushed to the limit: long-range mode from a few metres, smaller far packets, 5-min full-power search |
| **MK18 Supernova** | No more freezing, steadier connection (no false alarms), first-tap keyboard, watch-only photos, signal bars fixed, 6 new effects |
| MK17.1 Steady | Fixed the app closing after the PIN · KK themes |
| MK17 Bastion | A new key for every message · top-level pairing · hourly re-keying |
| MK16 Citadel | ML-DSA-87 + SHA-384 (CNSA 2.0 style) · intruder skull fix |
| MK15 Vault | App lock PIN · ML-KEM-1024 |
| MK14 Aurora | Theme icons · gradient glass · away mode · message effects |
| MK13 Arcade | Connect 4, Memory Match, Dice Duel · Discord-style text |
| MK12 Velvet | Theme folders · soft glass · Dynamic Island |
| MK11 Overdrive | Half the size · emoji safety code |
| MK9 Prism – MK10 Eclipse | Tilt shimmer, tap ripples · correct versions, battery saver |
| V1 – V8 | The original: offline encrypted chat, calls, games, liquid glass themes |

---

## 🗂️ Repository layout

| Folder | What it is |
|---|---|
| `WristRadar/` | The Wear OS app (Kotlin, Jetpack Compose for Wear OS, Room, Tink, Bouncy Castle) |
| `WristRadarPC/` | The Windows companion (C#, WinForms, WinRT Bluetooth LE, NAudio, Bouncy Castle) |
| `WristRadar/SECURITY.md` | The cryptographic design, threat model and limitations |
| `WristRadar/TESTING.md` | What has been tested, and how |
| `docs/` | Images for this page (`docs/make_banner.py` draws the banner) |

## 🔧 Building

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

## 📜 Licence

Source-available, **not open source**. You may read, study, build and use it, but you may not
re-publish, redistribute, modify publicly or sell it. See [`LICENSE`](LICENSE).
Third-party components keep their own licences, listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

<p align="center"><sub>Made with 💜 on a Galaxy Watch FE · no clouds were harmed</sub></p>
