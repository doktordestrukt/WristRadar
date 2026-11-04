# WristRadar V1 — Security architecture

WristRadar aims to be a private, authenticated, one-to-one channel between two watches. It is **not
unhackable**. This document describes what it protects against, what it does not, and what has been verified.

## 1. Dependencies (no custom cryptography)

| Purpose | Primitive | Library |
|---|---|---|
| Device identity | Ed25519 | Google Tink 1.18 (`subtle`) |
| Classical key agreement | X25519 (rejects low-order points) | Google Tink |
| Post-quantum KEM | **ML-KEM-768** (FIPS 203) | BouncyCastle `bcprov-jdk18on` 1.84, lightweight API |
| Key derivation | HKDF-SHA-256 | Google Tink |
| Message encryption | AES-256-GCM, 96-bit nonces, 128-bit tags | Android JCA / BoringSSL (Conscrypt) |
| Hashes / MAC | SHA-256, HMAC-SHA-256 | Android JCA |
| At-rest key | AES-256-GCM, non-exportable, TEE-backed | Android Keystore |

Both libraries are actively maintained, pure Java/Kotlin and work on Wear OS 4+ (API 33+).
The project only *composes* standard primitives, in standard ways: SAS pairing, SIGMA-I with a PSK, a hybrid KEM
combiner via HKDF, and an HKDF symmetric ratchet. No new algorithm is invented.

## 2. Identities

On first launch each watch generates an **Ed25519 identity key**. The 32-byte seed is stored only after it has
been encrypted with the Keystore key, and it is never transmitted. The username and avatar are display data only.

## 3. Pairing: authenticated, MITM-resistant, hybrid PQ secret

```
A → B  PAIR_COMMIT   c = H("WR-COMMIT" ‖ ephA ‖ idA ‖ nA), name
B → A  PAIR_RESPONSE ephB, idB, nB, kemPkB (ML-KEM-768), name      (after B's user accepts)
A → B  PAIR_REVEAL   ephA, idA, nA, kemCt = Encaps(kemPkB)         (B checks c)
th  = H("WR-PAIR-V2" ‖ c ‖ ephB ‖ idB ‖ nB ‖ ephA ‖ idA ‖ nA)
SAS = HKDF(X25519, th, "wr sas") mod 10⁶   → "123 456" on both watches; the users compare
th2 = H("WR-PAIR-PQ" ‖ th ‖ kemPkB ‖ kemCt)
A ↔ B  PAIR_CONFIRM  Ed25519 signature over (role ‖ th2)
psk = HKDF(X25519 ‖ ML-KEM secret, th2, "wr psk v2")
```

- A commits before seeing B's key, so a man-in-the-middle gets one 10⁻⁶ guess per attempt. A failed guess is
  visible to the users as mismatched codes.
- **The SAS covers only committed values on purpose.** The KEM ciphertext is chosen after the commitment. If it
  fed the SAS, an attacker could try many ciphertexts until the codes matched. The KEM values are instead
  authenticated by both identity signatures over `th2`.
- Proximity is never treated as proof of identity. Only the code comparison is.

## 4. Every connection: authenticated, hybrid, forward-secret handshake

```
C→S  ephC ‖ nC ‖ kemPkC
S→C  ephS ‖ nS ‖ kemCt ‖ AEAD_hs( idS ‖ Sig_S("WR-HS-S" ‖ H(t1)) )
C→S          AEAD_hs( idC ‖ Sig_C("WR-HS-C" ‖ H(t2)) )
ikm = X25519(ephC, ephS) ‖ ML-KEM secret ‖ psk
c2s, s2c = HKDF(ikm, H(full transcript), "wr c2s" / "wr s2c")
```

- A peer needs the **pairing PSK and the pinned identity key**. Anything else fails and is disconnected.
- Fresh X25519 and ML-KEM keys on every connection give forward secrecy. They also give **post-compromise
  recovery**: leaked session keys stop working at the next handshake.
- **Session renewal**: after 3 hours, when idle, the client drops the link and runs a brand-new handshake.

### Post-quantum claim, stated carefully

The secret is protected if **either** X25519 **or** ML-KEM-768 holds, because both feed HKDF together with the
PSK. This targets *harvest now, decrypt later*: an attacker recording BLE traffic today to decrypt it with a future
quantum computer. **Authentication is classical** (Ed25519 plus the human SAS check). That is fine for this threat,
because authentication only has to hold at the moment of pairing or connecting. The ML-KEM code is BouncyCastle's.
The project's own tests check round-trips and implicit rejection only, not NIST test vectors, and the
construction has not been independently audited.

## 5. Records

`epoch(1) ‖ counter(8) ‖ AES-256-GCM(ciphertext ‖ tag)`. The header is authenticated as AAD.

- **Unique nonces**: direction byte plus a strictly increasing 64-bit counter, with separate keys per direction.
- **Replay protection**: a counter at or below the last accepted one is dropped. It is classed as *replay* only if it
  is an authentic old record. Anything else is classed as a *forgery*.
- **Key rotation**: a one-way HKDF ratchet every **200 records or 10 minutes**. Old keys are wiped.
- **Application layer**: message UUIDs and sequence numbers (deduplicated with insert-ignore), image SHA-256 checks
  and a decode test.
- **Encrypted payloads**: text, image bytes, image metadata, profile updates, battery and RSSI status, and unpair
  notices all travel encrypted.

## 6. Signal Protocol / Double Ratchet: evaluated, not adopted in V1

| | Signal (libsignal) | WristRadar V1 |
|---|---|---|
| Designed for | Asynchronous delivery through a server, with prekeys | Live, synchronous BLE sessions between two devices |
| Per-message FS + PCS | Yes (Double Ratchet) | FS per 200-record epoch. PCS at every handshake (each reconnect, and at least every 3 h) |
| Library | `org.signal:libsignal-android`: Rust core with JNI, several MB per ABI, **AGPL-3.0** | Tink plus BouncyCastle, pure JVM |

libsignal is well reviewed. For this design, though, a Double Ratchet mostly protects *offline, out-of-order*
messages, and BLE sessions here are online and ordered. Its AGPL licence also binds the whole app. Hand-rolling a
Double Ratchet would be exactly the "homemade cryptography" this project avoids. V1 therefore takes the same
security goals from short-lived hybrid sessions plus a symmetric ratchet. **Recommendation:** if V2 adds store-and-forward
messages, adopt libsignal (PQXDH plus Double Ratchet, or its newer triple ratchet) rather than extending this protocol.

## 7. BLACKBOX intrusion detection

Raised only on cryptographic evidence, never on radio presence:

| Event | Where detected |
|---|---|
| `AUTH_FAILED` | Server: HS_FINISH fails PSK or signature verification |
| `IMPERSONATION` | Client: a device advertising the partner's secret rolling token fails the handshake |
| `UNAUTHORISED_DEVICE` | Server: encrypted records on a link that never authenticated |
| `TAMPERED_MESSAGE` / `REPLAYED_MESSAGE` | A record fails its GCM tag, or is an authentic replay |
| `PAIRING_TAMPER` | The reveal does not match the commitment, or the confirmation signature is invalid |
| `CODE_MISMATCH` | The user taps *Codes differ*. This is the classic MITM signal, but it could also be a mis-tap |
| `LOCAL_TAMPER` | A stored message fails Keystore AES-GCM authentication |

- Device shown: the Bluetooth name if the system has one, otherwise **UNKNOWN DEVICE**, plus the BLE address.
  That address is a *random, rotating* address, so it does not identify a person.
- Alerts never overlap, and there is a 20 s cooldown. The log is local only (Room, max 500 entries) and holds no
  content or keys. The detector only reports what failed. It never claims that a specific person was decrypting anything.
- A device that fails authentication is sent a `BLACKBOX` frame and disconnected. This only affects devices that
  already failed cryptographic checks, so it never interrupts the legitimate partner.

## 8. What travels in clear

- **Adverts**: the service UUID, a discoverable flag, a discovery ID (only while the Nearby screen is open), and a
  rolling 8-byte token that changes every 10 minutes. No name is advertised.
- **Discovery profile** (only while on the Nearby or pairing screens): username and a 64 px avatar.
- **Pairing and handshake public values**: these are public by design and are authenticated as described above.

## 9. Data at rest

- The identity seed, PSK, message bodies, received pictures and the partner's avatar are AES-GCM encrypted with a
  Keystore key, on top of the watch's own file-based encryption.
- Message metadata (times, status) and the security log are stored in Room unencrypted.
- Backup and device transfer are disabled. **Unpair** wipes the trust keys and the conversation.

## 10. Threat model: what is NOT covered

- Someone who has your unlocked watch, or root, ADB or OS-level access to it.
- Users who confirm the codes without actually comparing them.
- Traffic analysis: seeing that two BLE devices talk, when, and roughly how much.
- Jamming or connection flooding (denial of service).
- Linking adverts within one 10-minute token window. Exposure of the username and avatar while in discovery mode.
- Bugs in Tink, BouncyCastle, BoringSSL or the Android Keystore. There has been no independent audit.

## 11. Verification status

18 JVM unit tests exercise the real protocol code (see TESTING.md). **None of the security properties have yet
been tested on two physical Galaxy Watch FE watches.** Do not rely on WristRadar for high-risk communication
until that is done and the design has had an independent review.
