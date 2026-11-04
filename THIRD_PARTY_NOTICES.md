# Third-party notices

WristRadar's own code is under the WristRadar Source-Available License (see `LICENSE`). It uses the
following third-party components. None of them are stored in this repository: Gradle (watch app)
and NuGet (PC app) download them at build time. Each remains under its own licence.

## Watch app (`WristRadar/`)

| Component | Licence |
|---|---|
| Google Tink (`com.google.crypto.tink:tink-android`) | Apache License 2.0 |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`) | MIT-style Bouncy Castle Licence |
| AndroidX / Jetpack (Compose, Wear Compose, Room, Lifecycle, Activity, Core, Wear Input, Wear Ongoing) | Apache License 2.0 |
| Kotlin standard library and kotlinx.coroutines | Apache License 2.0 |
| Google Play services Wearable (`play-services-wearable`) | Android Software Development Kit License / Google APIs Terms |

## PC companion (`WristRadarPC/`)

| Component | Licence |
|---|---|
| Bouncy Castle for .NET (`BouncyCastle.Cryptography`) | MIT-style Bouncy Castle Licence |
| NAudio | MIT License |
| System.Security.Cryptography.ProtectedData | MIT License |
| .NET, Windows Forms and the Windows SDK projections | MIT License / Microsoft terms |

## Algorithms

X25519, Ed25519, HKDF-SHA-256, AES-256-GCM and ML-KEM-768 (FIPS 203) are public standards, implemented
by the libraries above. IMA ADPCM, the voice codec, is a public algorithm reimplemented here.
