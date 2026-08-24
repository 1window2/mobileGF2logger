# Changelog

All notable changes to mobileGF2logger are documented here.

## 2.4.0 - 2026-08-24

### Added

- Detect each Platoon from the authoritative `21905` identity plus Android's
  supported-client flow ownership, and keep separate HaoPlay/Darkwinter and
  server-region profiles in the Home, Platoon, Weekly, and Settings selectors.
- Add independently configurable HaoPlay and Darkwinter capture-server
  presets, scoped database/CSV/checkpoint/report settings, and profile-aware
  format-v3 `.gf2backup` archives.

### Changed

- Quarantine up to 32 decoded payloads per TCP flow until both its supported
  Android client and valid Platoon identity are known; unverified flows never
  enter management storage.
- Keep one-time-capture completion evidence isolated per detected Platoon so
  two clients cannot accidentally complete one checklist.
- Preserve v2.3.x data as an unmoved legacy profile while new Platoons use
  deterministic private databases and retained-evidence directories.

### Fixed

- Resolve all six review findings from v2.3.3: clean flow metadata without a
  parser, order known same-day joins by instant, bound manual weekly notes,
  preserve captured member names, replace timezone-derived history atomically,
  and accept `21905` checklist evidence only after identity validation.
- Bound the profile registry, profile metadata, and pre-identity flow buffer;
  reject invalid restores before metadata changes, preserve the selected import
  scope through preview/apply, and restore the matching client-region routing.
- Quarantine identity-changing or admission-failed flows, admit at most one new
  profile per client per user-started capture, and let users forget selector
  metadata without deleting the isolated Platoon data.

## 2.3.3 - 2026-08-24

### Added

- Add required Platoon Profile (`21905`) decoding for bounded platoon ID, name,
  and emblem/style identifiers, plus original-flow owner attribution on Android
  10 and newer as the safe foundation for future multi-Platoon isolation.
- Add server-region reset presets and phone-local reset conversion, retain a
  manual game-timezone fallback, and include the selection in complete backups.
- Add deterministic post-condition audits for membership timelines and weekly
  revisions, plus randomized sparse Gunsmoke solver coverage.

### Changed

- Store each weekly-table history entry as a complete immutable render
  revision, including the names, private notes, weekly notes, and membership
  events visible at that time.
- Upgrade the management database to schema v13 and the complete-settings
  payload to schema v4 while preserving older database and backup formats.

### Fixed

- Keep member active status synchronized after adding, editing, deleting, or
  reopening membership periods, and reject overlapping periods or multiple
  open periods before committing a manual edit.
- Validate date-only membership boundaries by their recorded calendar dates,
  rather than by the unrelated time when those dates were entered.
- Repair stale active-member flags when upgrading an existing database.
- Rebuild history safely when the configured game timezone changes, and avoid
  mixing restored historical tables with current member facts.
- Keep committed member, packet, note, and override changes authoritative if a
  derived history refresh fails, and fall back from stale restored projections.

## 2.3.2 - 2026-08-23

### Added

- Keep up to 15 immutable revisions for each weekly table after visible packet,
  import, or manual-edit changes, with a timestamped history list, read-only
  preview, and explicit restore action.
- Add 512 generated sparse-capture routines and focused reset/final-event
  regressions to the weekly inference test matrix.

### Changed

- Simplify the capture information popup to the fixed HaoPlay and Darkwinter
  package IDs, and label the section as supported client IDs.
- Return a restored weekly table to the live projection automatically when new
  evidence changes that week.

### Fixed

- Derive packet-triggered weekly-history work only from validated, bounded
  activity and update observations, with an independent 32-week fan-out cap.
- Reconcile a captured Sunday 50-point prefix with the remaining 40-point tail
  proven by Monday's counter reset.
- Preserve exact weekly Login and Daily Patrol aggregates, and per-field daily
  consensus, even when the precise daily merit placement remains ambiguous.
- Use the final Gunsmoke packet to retain safe aggregate and per-day consensus
  across missing intermediate capture days.

## 2.3.1 - 2026-08-22

### Added

- Add simultaneous HaoPlay and Darkwinter capture by registering both verified
  Android package IDs as separate VPN application allowlist entries.
- Add bilingual in-app guidance that distinguishes Android package IDs from
  network domains and protocol prefixes.

### Changed

- Fix the verified HaoPlay (`com.haoplay.game.and.exilium`) and Darkwinter
  (`com.Sunborn.SnqxExilium.Glo`) package IDs as the two supported clients,
  replacing the ambiguous editable target field with compact read-only fields.
- Refine segmented controls, packet-history evidence rows, settings spacing, and
  member-list rhythm for more consistent compact layouts.

## 2.3.0 - 2026-08-17

### Added

- Add a five-page first-launch guide for Main, Settings, Platoon management,
  weekly-table controls, and parsed-packet pages. The bilingual segmented
  selector persists its language choice immediately, while Skip and Get
  started permanently complete onboarding for that installation.
- Add System, Light, and Dark appearance choices to Settings and preserve the
  selected theme in complete backups.

### Changed

- Refresh every Activity with a compact, black-and-white-first interface,
  restrained accent color, flatter geometry, accessible touch targets, and
  theme-aware packet and weekly-table cells.
- Refine the bilingual onboarding copy around the actual capture, evidence,
  backup, Discord, and weekly-table workflows, enlarge its page icons, and use
  compact `1/5` progress labels in both languages.
- Preserve existing unified settings while migrating upgraded v2.2.0 installs
  past the first-use guide; fresh installs still receive onboarding once.
- Extend complete-backup settings to schema v2 while restoring v1 backups with
  safe theme and onboarding defaults.

### Fixed

- Explicitly resolve the build-only Kotlin Gradle Plugin to patched
  `2.4.20-Beta1`, replacing the vulnerable `2.2.10` version pulled transitively
  by Android Gradle Plugin 9.3.1 and giving Dependabot a direct manifest entry
  it can update in the future.

## 2.2.0 - 2026-08-11

### Added

- Make every weekly metric cell explain its value, certainty, and supporting
  facts when tapped, and add an Evidence Health panel summarizing observed,
  exact, lower-bound, unknown, Login, Daily Patrol, and closing-boundary data.
- Add guided capture with a live Members, Activity, and Updates checklist that
  stops automatically when all three management payloads have arrived.
- Preview the effects of one or more roster CSV files before mutation, create a
  private automatic checkpoint before import, and provide one-level undo for
  the most recent confirmed import.
- Export the displayed weekly table as a bounded PNG or share it through
  Android, with independent controls for member names, UIDs, and private notes.
  UIDs and private notes remain excluded by default.
- Send a parsed packet's validated original CSV body to a user-configured
  Discord incoming webhook after explicit confirmation. The webhook is stored
  with Android Keystore encryption and is never displayed after saving.
- Add a security policy with private-reporting and data-redaction guidance.

### Changed

- Move weekly navigation and stale-result validity into a lifecycle-independent
  state holder, persist the selected week across recreation, and recycle heavy
  weekly member rows in a bounded `RecyclerView` viewport.
- Extract standard-week counter inference from report assembly and extract
  weekly snapshot projection SQL from the schema and transaction helper.
- Pin every GitHub Action to a verified commit SHA and make unsigned CI artifact
  names commit-specific instead of carrying a stale release version.
- Add deterministic randomized invariants for standard-week inference and
  malformed protocol streams alongside focused tests for evidence, sharing,
  guided capture, import preview, checkpoint identity, and webhook policy.
- Clarify guided capture, stop, and Discord actions with accessible color,
  Discord icons, aligned packet actions, and an explicit webhook-availability
  indicator.

### Fixed

- Reject stale asynchronous weekly renders after navigation, pause, or screen
  recreation and preserve the selected reporting date instead of allowing
  overlapping week loads to replace each other.
- Keep newly selected CSV sources unretained until the user confirms the impact
  preview, recover unfinished checkpoints before reading the preview baseline,
  reconcile crash-left evidence before classification, cap multi-file imports,
  preserve the preceding undo until a replacement import seals, and recover
  database/quarantine state after a failed import, undo, or process death.
- Bound weekly PNG dimensions, pixel count, row count, and private-note length;
  preserve pending save state across activity recreation, and share only through
  a cache-scoped non-exported `FileProvider` grant. Give every render a fresh
  UUID-backed cache identity and revoke prior URI grants before removing stale
  files so an earlier recipient cannot read a later privacy projection.
- Restrict Discord destinations to canonical HTTPS `discord.com` incoming
  webhook URLs, disallow redirects, and bound request/response sizes and timeouts.

## 2.1.2 - 2026-08-09

### Changed

- Re-center the launcher emblem against the real source canvas, reduce it by
  10%, and brighten the matte silver and gold-orange finish without redrawing
  its silhouette or introducing resampling artifacts.
- Make the English project summary more descriptive for people searching for a
  GIRLS' FRONTLINE 2 or GF2 Platoon management companion.

### Fixed

- Bound parsed-packet storage and table rendering by input size, row count,
  column count, cell count, and cell length so hostile or corrupt payloads
  cannot create unbounded Android view trees.
- Bound multi-part protocol continuations by byte and fragment counts, discard
  an oversized pending dataset, quarantine every remaining fragment through its
  terminal frame before decoding it, and enforce the byte cap from the first
  continuation. A malformed terminal fragment now also clears quarantine so a
  later legitimate payload of the same type is not discarded.
- Bound roster imports, per-payload activity observations, retained activity
  history, and unresolved-name reconciliation. Failed or duplicate roster
  imports now roll back their newly retained evidence as one operation.
- Apply the retained-activity cap after both activity packets and Updates-
  derived Daily Patrol facts, without waiting for a database reopen.
- Neutralize spreadsheet formulas in every captured roster, activity, Updates,
  and formation packet table as well as all spreadsheet-facing CSV exports,
  while preserving internal roster evidence losslessly.
- Upgrade schema-v6/v7 databases directly through the v10 activity-table
  rebuild without attempting to recreate already-installed identity indexes.
- Reconcile an observed roster absence against an exact or annotated open
  membership period, preserving its strong boundary and note while creating a
  later rejoin period instead of suppressing the gap. Annotated weak boundaries
  can still expand when late roster evidence proves a wider presence interval.
- Preserve an imported roster's parsed capture time so exporting the latest
  Platoon CSV cannot accidentally select a newly imported historical file.
- Roll back all nested SQLite reconciliation writes together with newly
  retained roster files when a multi-file import fails.
- Rotate bounded unresolved-activity reconciliation through the complete
  retained backlog so an unmatchable recent batch cannot starve older evidence,
  backed by a global capture-time retention index.
- Enforce roster member-count and name-length limits at live-capture writing,
  repository, and SQLite boundaries as well as during user-selected CSV import.
- Version the new retention index and maintenance cursor as schema v11 so v10
  backups migrate through the same strict current-schema validation path.
- Break equal capture-time ties by retained evidence filename when selecting
  the latest Platoon CSV for export.
- Accept a capped pre-Gunsmoke opening anchor only when it belongs to the
  immediately preceding Monday-through-Saturday counter period, using the
  configured game-day timezone across daylight-saving transitions.

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
