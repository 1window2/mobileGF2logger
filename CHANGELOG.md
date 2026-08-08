# Changelog

All notable changes to mobileGF2logger are documented here.

## Unreleased

## 2.1.1 - 2026-08-08

### Added

- Import one or more user-selected Platoon roster CSV files through Android's
  document picker with strict UTF-8/schema validation, bounded reads, stable
  content identities, durable retention, and duplicate detection.
- Delete an individual membership period from member details after explicit
  confirmation. The database keeps at least one period per member and cleans
  linked inferred events atomically.

### Changed

- Replace the ambiguous user-facing term `tenure` with `membership period` for
  one interval and `membership history` for the collection. Legacy v1-v9 table
  and column names remain recognized only for backup and database migration.
- Refine the launcher emblem with a subtle matte-silver finish and a warmer
  gold-orange star while preserving its silhouette, scale, and placement.

### Fixed

- Accept a pre-Gunsmoke boundary capture outside the narrow reset window when
  its previous standard-week merit counter is already at the absolute 540-point
  cap. This makes the July 18 anchor conclusively resolve July 19 merit, score,
  attempts, Login, and Daily Patrol without guessing.
- Reconstruct inactive members, withdrawals, and repeat membership periods when
  older roster CSVs are imported after newer structured data. Manual and exact
  game-update boundaries remain authoritative during replay.
- Show a clear failure state when automatic retained-CSV reconciliation fails
  instead of silently leaving the Platoon screen empty.
- Neutralize formula-like member names and notes in every spreadsheet-facing
  CSV export while keeping retained roster evidence lossless for re-import.

## 2.1.0 - 2026-08-01

### Added

- Add complete `.gf2backup` export and atomic restore for app settings,
  structured Platoon/member/membership history, and all weekly-table evidence.
- Validate complete-backup extension, archive identity, manifest, checksums,
  settings schema, SQLite integrity, current schema, and foreign-key references
  before replacing user data.
- Export all available weekly tables as one chronological CSV with one header.
- Add direct access to Weekly Table from the main screen.
- Add coordinated manual member deletion with explicit destructive-action
  confirmation and transactional cleanup of linked member records.

### Changed

- Emphasize Prepare Capture, Open Platoon Management, and Weekly Table with a
  restrained filled accent style and accessible light/dark contrast.
- Keep the v1 Platoon-only `.gf2backup` path available for compatibility while
  requiring complete v2 backups for Settings restore.
- Materialize retained completed roster CSV files before backup, then retire
  the target device's unrelated retained CSV cache after a successful complete
  restore so the selected archive remains deterministic. Failed restores roll
  the database, settings, and retained cache back together.
- Reconcile retained completed roster CSV files idempotently before management,
  weekly-table, and backup operations. Incomplete captures remain unpublished,
  and screen reconciliation runs outside the UI thread.
- Accept spreadsheet-copied roster CSVs with surrounding scalar whitespace and
  blank protobuf-optional counters while still rejecting malformed values.
- Distinguish exact weekly metrics from confirmed lower bounds and unknown
  values. Daily Gunsmoke participation at the three-attempt cap, its paired
  score, and any whole-week attempt total shared by every valid final-event
  history now display as exact values, even when the shortfall day is ambiguous.
- Use the captured single-attempt high score and per-attempt rounding equations
  to constrain Gunsmoke paths, and derive conservative end-event Login, Daily
  Patrol, merit, and attempt totals without requiring a synthetic 05:00 anchor.
- Preserve every fully solved daily prefix when a later transition reaches the
  search budget, reject pre-05:00 captures as exact next-day openings, and merge
  aggregate fallback results with stronger timestamped and daily lower bounds.
- Highlight exact values and confirmed lower bounds that remain below a
  configured cutoff, while leaving unknown values and minimums that already
  meet the cutoff neutral.
- Keep ambiguous Standard Week allocations unknown instead of selecting a
  Monday-first estimate. Solve each contiguous Gunsmoke capture run
  independently so an earlier missing day cannot suppress later exact facts;
  negative attendance or Daily Patrol remains unknown until a boundary or
  direct fact proves it.
- Preserve exact 05:00 closing boundaries, require timestamp ordering before a
  Daily Patrol Updates fact can finalize captured Gunsmoke merit, and keep
  untouched inferred fields provisional when manually correcting one field.

### Fixed

- Keep v1 Platoon-only restore deterministic by atomically retiring retained
  target-device roster CSV evidence that is not represented by the backup.
- Keep v1 Platoon-only restore independent from unrelated local settings and
  classify malformed ZIP structures as invalid backups instead of I/O failures.
- Prevent previous/next weekly-table navigation from blocking Android input:
  load immutable report projections on a serialized worker, discard stale
  results, build table rows in frame-sized batches, and bound the Gunsmoke
  counter solver with conservative fallback for pathological ambiguity.
- Show the incomplete-evidence warning when a weekly row contains a wholly
  unobserved packet day.
- Roll a failed complete restore back to a genuinely database-free fresh-install
  state, and keep strict schema validation compatible with Android 8 SQLite.

### Release verification required

- Before publication, verify that the exact merged v2.1.0 APK remains on the
  permanent v2.0.2 signing lineage and updates v2.0.2 in place without clearing
  application data.

## 2.0.2 - 2026-07-30

### Changed

- Start the permanent production-signing lineage with a dedicated private key.
- Remove a redundant recent-login and Daily Patrol caution from the Standard
  Week evidence explanation.
- Label CI's unsigned release APK as verification-only and package it with a
  non-distribution notice.

### Security

- Reject distributable release builds when the production keystore is missing,
  incomplete, unreadable, or signed by a certificate outside the pinned
  production lineage.
- Restrict unsigned release packaging to explicitly opted-in CI verification
  builds.

### Migration notice

- v2.0.2 cannot update v2.0.1 or earlier in place because those releases used
  the legacy Android debug certificate. Export any needed management backup,
  uninstall the old version, install v2.0.2, and import the backup.
- Future releases remain update-compatible with v2.0.2 when signed by the same
  permanent production key.

## 2.0.1 - 2026-07-29

### Added

- Parse mandatory Platoon Updates payload `21960` with exact member UIDs,
  event kinds, and Unix timestamps.
- Apply exact Updates evidence to Join/Withdraw history and UID-resolved Daily
  Patrol facts.

### Fixed

- Reconcile exact Updates events with nearby manual and snapshot boundaries so
  the same Join or Withdraw event is shown only once.
- Reuse an exact Updates membership period when the following roster snapshot confirms it,
  avoiding duplicate open membership periods and withdrawal-ingestion rollback.
- Preserve the current open membership period when a captured Updates feed contains an
  older withdrawal, and reject roster confirmation from a withdrawal
  superseded by a later rejoin.
- Backfill device-local calendar dates for pre-2.0.1 manual membership
  boundaries so they remain stable after timezone changes.
- Retry transient non-blocking TUN backpressure before treating the affected
  connection as failed.
- Preserve rapid rejoin/withdraw histories when an opposite boundary separates
  otherwise nearby same-side events, and merge safe inferred shadow membership periods
  without discarding their independent boundary.
- Present Join/Withdraw history as a compact borderless two-column table,
  including exact device-local times and an unknown-date group for inferred
  events.
- Use locale-independent identity keys when correlating roster names.
- Localize Activity and Updates packet-history badges in Korean.
- Require dates but allow unknown times for manually entered membership
  boundaries, displaying date-only membership-period summaries and `??:??` in weekly
  Join/Withdraw rows.
- Reconcile standard-week merit against every captured counter and recent-login
  timestamp instead of finalizing unsupported daily estimates.
- Treat only UID-safe Updates kind `8` records as exact Daily Patrol evidence.
- Keep packet-derived membership boundaries immutable without locking the
  editable manual boundary or private note on the same membership period.
- Hide internal evidence-precision labels from membership-history buttons.
- Place Join/Withdraw events by their device-local calendar date while merit
  calculations continue to use the 05:00 game-day boundary.
- Allow enough one-shot navigation time to open both Members and Updates.
- Recreate the bounded parser worker for each capture session and quarantine a
  TCP flow after queue overflow instead of parsing later chunks out of sequence.
- Keep native TUN I/O non-blocking so stopping capture cannot wait on a blocked
  device write.
- Load weekly snapshots with one period-bounded query instead of an N+1 query
  loop and an arbitrary 1,000-snapshot history cap.
- Serialize database maintenance against repository access, validate backup
  integrity and required schema, and retain rollback data until a restored
  database opens successfully.
- Run exports and management backup operations away from the Android main
  thread.
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
  membership periods, roster events, manual corrections, and weekly notes.
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
