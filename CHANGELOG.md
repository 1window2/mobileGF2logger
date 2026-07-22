# Changelog

All notable changes to mobileGF2logger are documented here.

## 1.1.0 - 2026-07-23

### Added

- A separate saved-packet collection that retains up to 50 manually selected history entries.
- A cleaned, horizontally scrollable table as the default packet-detail view.
- Access to the complete raw CSV text and clipboard copy from the table view.
- Payload-type labels for recent and saved packet history.
- A payload-options screen for Weapons, Attachments, Common Keys, and Formations while keeping Platoon Members permanently enabled.
- C/C++ CodeQL analysis with the extended security query suite.

### Changed

- User-facing Circle and Guild terminology now consistently uses the official term Platoon.
- Newly generated member exports use `gf2log_platoonmembers_*.csv` filenames.
- Optional non-Platoon payloads are excluded from packet history unless explicitly enabled.

### Fixed

- Display history timestamps in the Android device timezone while keeping CSV timestamps in UTC.
- Prevent unsigned underflow in zdtun's open-socket counter.
- Drain queued flow-close parser work before clearing capture state.
- Keep message-zero payload batches separate when a TCP flow closes.

### Verified

- Unit tests, Android lint, ARM64 native compilation, R8 shrinking, resource optimization, and release assembly pass.
- Standalone device capture, payload options, history tags, saved history, table/raw views, clipboard copy, and deletion were verified on a Samsung SM-N976N running Android 12.

## 1.0.0 - 2026-07-22

### Added

- Lightweight, non-root, per-app Android VPN capture for the supported game package.
- On-device parsing of the five known game response types, including Platoon member payload type `21917`.
- UTF-8 Platoon-member CSV generation with collision-safe filenames.
- Private, newest-first history for the latest 100 parsed packets, including detail, copy, export, selection, and manual deletion actions.
- Android backup exclusions for captured data and a dedicated launcher icon.

### Fixed

- Flush a pending recognized payload when its TCP flow closes.
- Clear continuation state after a parser overflow instead of combining unrelated data.
- Report parser backpressure rather than silently discarding queued payload chunks.
- Keep capture status process-local so an app restart cannot display stale running state.
- Detect unexpected native forwarding termination and release VPN resources.
- Write history entries atomically and avoid overwriting CSV files captured within the same second.

### Verified

- Unit tests, Android lint, R8 shrinking, and the release build pass with JDK 17 and Android SDK 36.
- Standalone end-to-end capture was verified on a Samsung SM-N976N running Android 12 with ADB disconnected: the app forwarded live game traffic and parsed a 40-member Platoon response.
