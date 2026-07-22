# GF2log

GF2log is a lightweight, non-root Android prototype for parsing selected Girls' Frontline 2 server responses on the device that owns the traffic. It follows the protocol-framing approach demonstrated by [`blead/gfl2logger`](https://github.com/blead/gfl2logger), while replacing the desktop `mitmproxy` capture layer with an Android per-app `VpnService` design.

## Current status

The repository contains a working, tested protocol layer and a minimal Android shell:

- Persistent TCP-stream framing across fragmented and coalesced reads.
- Zero-dependency Protocol Buffers wire decoding for the five known response types.
- Typed Kotlin models for weapons, attachments, common keys, guild members, and formations.
- A package-scoped `VpnService` backed by the zero-dependency `zdtun` userspace forwarding core.
- Bounded parser buffers and a bounded single-worker queue.
- UTF-8 guild-member CSV export with the same columns and continuation behavior as gfl2logger.
- A newest-first, private history of the latest 100 parsed packets with detail and copy views.
- No Compose, AndroidX runtime, protobuf runtime, database, analytics, raw-packet storage, or network upload.

The ARM64 build has been exercised on a physical Samsung SM-N976N running Android 12. During the test, the selected game remained connected while downloading its resource pack through GF2log. TLS and HTTP asset flows are forwarded without copying their payload into Kotlin; only plausible plaintext TCP flows reach the game-protocol parser.

## Use

1. Install the APK on an ARM64 Android 8.0 or newer device.
2. Open GF2log and confirm `com.haoplay.game.and.exilium`, or enter another installed regional package name.
3. Select **Prepare capture** and approve Android's VPN dialog.
4. Open the game. Guild data is normally observed during login, reconnection, or when opening Platoon pages.
5. Return to GF2log to open or copy a recent parsed packet, select entries for manual deletion, or select **Export latest guild CSV**.

Parsed-packet history is stored privately under `files/capture-history`, capped at 100 entries in FIFO order. Guild CSV files are stored privately under `files/guild-members` until the user exports one through Android's document picker. The columns are:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## Build and test

Requirements are JDK 17 or newer and Android SDK 36.

```powershell
.\gradlew.bat :protocol:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease
```

The installable release APK is generated at `app/build/outputs/apk/release/app-release.apk`. The repository's release build currently uses Android's local debug signing identity for developer distribution; configure a private release keystore before public distribution.

## Modules

- `protocol`: platform-independent framing and protobuf-wire parsing logic.
- `app`: minimal platform-API UI, VPN consent flow, zdtun/JNI forwarding core, and CSV exporter.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the data flow, framing format, and native integration boundary. Contribution and commit rules are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Safety scope

GF2log is designed for traffic that the device owner is authorized to inspect. It does not include certificate-pinning bypasses, anti-cheat evasion, credential extraction, traffic modification, or gameplay automation. TLS connections are forwarded unchanged and excluded from parsing.

GF2log is licensed under GPL-3.0. Bundled third-party notices are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
