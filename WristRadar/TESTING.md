# WristRadar V1 — Test status

Only what was actually run is marked as passed.

## 1. Verified (2026-11-04, on the build machine)

| Check | Result |
|---|---|
| `assembleDebug` compiles (Kotlin + KSP/Room + resources) | ✅ PASS |
| `assembleRelease` (R8 minify, resource shrink, lintVital) | ✅ PASS |
| Release APK v2/v3 signature verified with `apksigner` | ✅ PASS |
| Manifest check with `aapt2`: minSdk 33, targetSdk 36, permissions, `connectedDevice` FGS type | ✅ PASS |
| `lintDebug`: no errors (warnings only) | ✅ PASS |
| JVM unit tests: 18/18 (`CryptoProtocolTest`) | ✅ PASS |
| Release APK contains the 4 bundled MP3s (`res/raw`) | ✅ PASS |
| Room database migration v1 → v2 schema exported (`app/schemas`) | ✅ PASS (compile-time only; not run on a device) |

The unit tests run the real protocol code (not mocks of it):

- Pairing codes match between honest peers, and both sides derive the same PSK.
- A reveal that does not match the commitment is rejected.
- A simulated man-in-the-middle relay produces **different** codes on the two watches.
- Forged or role-swapped pairing confirmations are rejected.
- The session handshake succeeds for a paired pair.
- The session handshake fails with a wrong or wiped PSK, from a stranger even when the PSK is known, and against an impersonated server.
- Every session gets fresh keys.
- Tampered ciphertext and tampered headers are rejected.
- Replayed and reordered records are rejected.
- Records from another session are rejected.
- The key ratchet stays in sync across 605 records (rotation every 200). This test found and fixed a key-aliasing bug.
- ML-KEM-768 round-trips. A tampered ciphertext yields a different secret (implicit rejection).
- A tampered ML-KEM ciphertext inside the handshake makes it fail.
- Both sides derive the same hybrid pairing PSK.
- Rejected records are classified correctly: an authentic replay counts as REPLAY, a forgery as AUTH. Blackbox uses this classification.
- Advert payload round-trips correctly, and tokens rotate.
- Distance output is coarse and smoothed.

## 2. NOT yet tested: requires two physical Galaxy Watch FE watches

No watch was connected to the build machine (`adb devices` was empty) and no Wear OS emulator image was
installed. Emulators cannot do BLE between two instances anyway. **None of the items below have been run yet.**
Run them with both watches and record the results.

| # | Test | How | Result |
|---|---|---|---|
| 1 | Both watches discover each other | Both open *Connect to nearby watches* | ☐ |
| 2 | Correct usernames and avatars shown | Set distinct names first | ☐ |
| 3 | Users can select each other | Tap the entry → profile screen | ☐ |
| 4 | Secure pairing works | Pair from one side; then try both pressing at once | ☐ |
| 5 | Verification codes match | Compare the 6 digits | ☐ |
| 6 | Both users confirm; mismatch path cancels | Also tap *Codes differ* once | ☐ |
| 7 | Pairing remembered after reopen and reboot | Force-stop, reboot | ☐ |
| 8 | Text messages both ways, ticks ✓✓ | | ☐ |
| 9 | Pictures both ways, progress shown | Use a photo stored on the watch | ☐ |
| 10 | Background notifications | Leave app, screen off, send from the other watch | ☐ |
| 11 | Vibration | Toggle in Settings → Notifications | ☐ |
| 12 | Username change syncs | Profile → Edit username | ☐ |
| 13 | Profile picture change syncs | | ☐ |
| 14 | Distance estimate updates | Walk apart and back | ☐ |
| 15 | Refresh connection works on both watches | | ☐ |
| 16 | History survives app restart | | ☐ |
| 17 | Theme persists | | ☐ |
| 18 | Unpair works; partner informed | | ☐ |
| 19 | Unknown device cannot access messages | Third WristRadar watch tries to connect. Expect *Rejected an unknown device* | ☐ |
| 20 | Tampered or replayed messages rejected | Covered by unit tests; on device, check `adb logcat -s WristRadar` | ☐ |
| — | Bluetooth off, then on again | Quick settings | ☐ |
| — | Temporary connection loss / out of range / back in range | Walk away more than 30 m | ☐ |
| — | Background service interruption | `adb shell am kill com.wristradar` | ☐ |
| — | Low battery behaviour | Battery saver on | ☐ |
| — | Interrupted image transfer resumes | Walk out of range mid-transfer, return | ☐ |

Useful logcat filter (it never prints message content):

```bash
adb logcat -s WristRadar
```

## 3. Hardware assumptions to confirm on the watch

- `BluetoothAdapter.isMultipleAdvertisementSupported` is true. The app shows *NOT SUPPORTED* if it is not.
- The GATT server and client can run at the same time. This is standard on Wear OS 4+.
- MTU 517 is negotiated. The app falls back to smaller fragments automatically.

## 4. New in this build: needs on-device testing (not yet done)

| Test | How | Result |
|---|---|---|
| Picture button opens the watch's gallery app | Chat → picture button | ☐ |
| Profile picture opens the gallery app | Profile → Change profile picture | ☐ |
| Fallback grid when no gallery app is present | *Browse watch photos here* | ☐ |
| Pairing still works with the larger hybrid PQ messages | Pair two watches from scratch. **V1 builds from before this change cannot pair or connect with this build. Update both watches.** | ☐ |
| Blackbox preview: skull, glitch, Glitch2 then looping music, Glitch3 on dismiss | Settings → Blackbox security → Preview alert | ☐ |
| Sound, music and vibration toggles respected | | ☐ |
| No Blackbox alert during normal use: scanning, chatting, walking out of range, refresh, the 3 h renewal | Use normally for a day | ☐ |
| Real CODE_MISMATCH event is logged and alerts | Tap *Codes differ* while pairing | ☐ |
| Background alert notification | Trigger an event with the app closed | ☐ |
| Security log lists events, opens with Glitch1, clears | | ☐ |
| Database upgrade keeps existing messages | Install over the previous build (same signature) | ☐ |

Events like AUTH_FAILED, IMPERSONATION, TAMPERED and REPLAYED need a modified client (a test harness) to trigger on
real hardware. The detection code paths are covered by the unit-tested crypto results they depend on, but the
end-to-end alert for those events has not been observed.

## 5. Found and fixed on real hardware (watch + PC)

- **Watch app crashed when a peer used a large MTU (517).** Notifications above 512 bytes throw on Android. Fragments are now capped at 512 and send errors no longer crash the app. This crash would also have hit watch↔watch links.
- **The PC failed to reconnect** with GATT "AccessDenied" because service objects weren't released. They are now released after each connection.
- **Verified:** watch↔PC pairing, hybrid handshake, automatic reconnect, and text and pictures in both directions.
- **Not yet verified on hardware:** calls, voicemail and voice messages, typing, presence, phone status, warning screens, and watch↔watch.
