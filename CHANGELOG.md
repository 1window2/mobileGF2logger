# Changelog

All notable changes to mobileGF2logger are documented here.

## Unreleased

### Fixed

- Project weekly rows from the latest complete active roster so withdrawn
  members disappear immediately while their immutable Join/Withdraw records
  remain in the weekly event section.
- Reconcile Standard Week cells from the latest Monday-based cumulative Weekly
  Merit counter so a newly parsed roster immediately updates the current day.
- Render fully constrained daily merit as a plain integer, retain `≥` only for
  genuine lower bounds, reject standalone 40-point increments as daily totals,
  and mark ambiguous Monday-first `90`/`50`/`0`
  allocations with `≈`.

## 2.0.0 - 2026-07-28

### Added

- One-shot Platoon roster capture with automatic VPN shutdown after a complete
  payload type `21917` response.
- A private structured Platoon database for snapshots, member status, repeat
  tenures, roster events, manual corrections, and weekly notes.
- Platoon-management screens for active and withdrawn members, snapshot
  comparison, selected-member CSV export, and explicit backup export/import.
- Weekly activity tables for Login, Daily Patrol, merit, Gunsmoke Frontline
  score, inferred attempts, totals, and rankings.
- Separate daily and weekly cut-off settings for merit, Gunsmoke score and
  attempts, weekly login days, and weekly Daily Patrol days.
- Member sorting by join date, Weekly Merit, or Total Merit in ascending or
  descending order.
- Persistent custom member ordering with long-press drag feedback and live row
  movement.
- Calendar-based weekly navigation plus clipboard actions for weekly and
  snapshot-comparison CSV data.
- A caution-gated weekly-table editor with persisted manual overrides for
  numeric activity values and three-state Login/Daily Patrol marks.
- Separate immutable Join/Withdraw event records and deletable manual Notes.
- English and Korean application language selection.
- Capture diagnostics and unknown-payload reporting.
- A GF2-inspired Platoon launcher icon using orange, off-white, and black.

### Changed

- Weekly calculations use the Android device timezone by default, a 05:00 game
  reset, a fixed Sunday-to-Saturday week, and the verified three-week Gunsmoke
  cycle.
- Standard Week tables omit Gunsmoke score and attempt cells; those cells are
  shown only during a Gunsmoke Frontline week.
- Gunsmoke tables rank members by score before merit and name.
- Weekly tables use compact, aligned grouped cells for Merit, Login, Daily
  Patrol, and Gunsmoke metrics.
- Native build caches are excluded from version control.
- User-facing Korean payload and management terms follow the project
  dictionary, including 서클, 파츠, 공용키, 가입, 탈퇴, 비고, and 주간 표.
- Platoon management opens with Active members and can sort by Total Merit.
- Sparse Standard Week captures preserve their supported Total Merit aggregate
  and use visibly approximate Monday-first `90`/`50`/`0` allocations;
  current in-progress game days are never finalized by that fallback.
- Sparse Gunsmoke captures retain unknown daily score/attempt cells and use
  captured weekly/score counters to strengthen the Total column.

### Fixed

- Discard incomplete roster batches instead of persisting partial member lists.
- Stop one-shot capture only after the complete roster is stored successfully.
- Start the VPN foreground service before legacy CSV migration work.
- Preserve restored Platoon history during backup migration.
- Localize payload-option labels and descriptions.
- Keep newcomers in the current weekly roster while leaving pre-join activity
  unobserved instead of marking it as missed.
- Count a confirmed newcomer's first observed weekly merit as activity since
  joining while retaining `-` for the preceding days.
- Infer zero Gunsmoke attempts from 50- or 90-merit days with no score gain.
- Anchor every calendar selection to its containing Sunday-to-Saturday week.
- Show parsed packet rows even when optional columns contain no values.
- Keep packet-derived Join/Withdraw events immutable while preserving reliable
  deletion for manual Notes.
- Preserve manually corrected member names across later roster captures.
- Retain the last observed same-day merit for members who withdraw before the
  final roster capture.
- Mark live same-day values as partial lower bounds, use Total Merit across the
  Monday weekly-counter reset, and keep sparse allocations distinguishable
  from exact 05:00-boundary evidence.
- Open weekly reports on the current 05:00-based game day before midnight.
- Derive Join/Withdraw placement from event timestamps in the current device
  timezone so those entries remain visible after timezone changes.

### Verified

- A Samsung SM-N976N running Android 12 captured a live Platoon Members
  response, persisted a new history entry and structured snapshot, and stopped
  one-shot capture without computer-side packet processing.
- A second live capture detected a 43-member roster after a 42-member snapshot;
  the three confirmed newcomers each showed 90 Merit and green Login/Daily
  Patrol marks on 7/27 while retaining `-` for their pre-join 7/26 cells.
- Standard and Gunsmoke table alignment, calendar navigation, drag ordering,
  localized cut-off settings, launcher icon, and CSV clipboard actions were
  visually checked on the same device.
- The signed v2.0.0 release captured and parsed a fresh 43-member roster at
  21:23 local time, stopped one-shot capture, updated packet history and the
  structured snapshot automatically, and reopened the weekly table.
- The caution dialog, editable numeric cells, three-state activity controls,
  save icon state, database migration, and persistent deletion of a manual
  note were verified on the same device.

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
