# Platoon management model

This document defines the management rules implemented by GF2logger. The
reference workbook is a leader-maintained Platoon management spreadsheet.
Spreadsheet labels and formulas are evidence, but capture timestamps and the
rules below take precedence over stale copied dates in archived templates.

## Terms

| English | Korean |
| --- | --- |
| Gunsmoke Frontline | 흙먼지 전선 |
| Daily Patrol | 일일 과업 |
| Merit This Week | 이번 주 공적 |
| Total Merit | 누적 공적 |
| Member | 멤버 |

## Time periods

- A game day begins at 05:00 and ends immediately before 05:00 the next day.
- A merit week begins Monday at 05:00 and ends the next Monday at 05:00.
- A Gunsmoke week begins Sunday at 05:00 and contains seven game days through
  the following Sunday at 05:00.
- Gunsmoke scoring closes at 02:00 on the final Sunday. A Saturday snapshot at
  or after 02:00 therefore confirms the final cumulative Gunsmoke score, while
  merit, login, Daily Patrol, and the Saturday game day remain open until
  05:00. A daily Saturday score delta still requires a trustworthy opening
  boundary; the app does not invent one from the final total alone.
- A capture shortly before 05:00 is not an exact opening boundary for the next
  day. Its following counter delta can include the previous day's tail, so the
  daily Gunsmoke cell stays unknown unless adjacent counters reconcile the split.
- Gunsmoke runs for one week followed by two off weeks. The verified cycle
  anchor is Sunday, 2026-07-19 at 05:00. Other verified starts are 2026-02-22,
  2026-03-15, 2026-04-05, 2026-04-26, 2026-05-17, 2026-06-07, and 2026-06-28.
- Period calculations use the configured game timezone. The initial default is
  the Android device timezone; stored capture instants remain UTC.

## Merit calculation

The daily non-Gunsmoke sources visible in the reference data are:

- Login attendance: 50 merit.
- Daily Patrol: 40 merit.

During Gunsmoke:

- A member can participate up to three times per game day.
- Each participation grants 30 merit.
- Each participation also grants `floor(attemptScore / 10)` merit. Remainders
  are discarded for each attempt, not after adding all attempt scores.

The member packet contains aggregate score rather than individual attempt
scores. For two snapshots, GF2logger therefore tests every attempt count from
zero through three. For an attempt count `n` and aggregate score delta `s`, the
possible score-merit range is:

```text
floor(s / 10) - max(n - 1, 0) .. floor(s / 10)
```

The calculation is accepted when the remaining merit is one of `0`, `50`, or
`90`. A unique match is reported as inferred. Multiple matches remain
ambiguous and are shown for manual confirmation; the app does not fabricate an
exact count. Three attempts are the daily cap, so they finalize the attempt
count and the paired captured score. Twenty-one attempts are the weekly cap and
finalize the weekly attempt count. The captured single-attempt high score is an
additional upper bound: when an aggregate saturates that bound, it can also fix
the attempt count and the otherwise ambiguous per-attempt rounding merit.

Counter resets are handled by treating a negative delta as the current counter
value. Daily activity is derived from Total Merit because Merit This Week
resets on Monday while a Gunsmoke week begins on Sunday.
A capped pre-Gunsmoke counter is a valid opening anchor only inside the
immediately preceding Monday 05:00-to-Sunday 05:00 calendar interval in the
configured game-day timezone; this remains correct across daylight-saving
transitions.

## Snapshots and weekly tables

- Every Platoon Members payload is stored as a structured snapshot keyed by its
  capture instant.
- Every supplied Platoon Activity entry is stored as an immutable timestamped
  fact. Only UID-safe Updates kind `8` / action `802001` supplies exact Daily
  Patrol evidence; a missing event never proves that Daily Patrol was skipped.
- Platoon Updates payload 21960 supplies UID-safe exact Join, Withdraw, and
  Remove timestamps.
- Non-Gunsmoke weeks constrain every `0/50/90` daily allocation against all
  captured Weekly Merit checkpoints. A 21917 recent-login time proves the
  member logged in on its 05:00-based game day and narrows that day to 50 or
  90; an exact Daily Patrol event fixes it at 90.
- Values shared by every compatible allocation are exact. Ambiguous completed
  days remain unknown instead of displaying an arbitrary allocation, while an
  unfinished day displays only its confirmed positive lower bound until it
  reaches the 90-point cap.
- Gunsmoke weeks additionally show daily score changes, inferred participation
  counts, cumulative Gunsmoke score, participation totals, and rankings.
- A positive Gunsmoke score or attempt proves attendance, but Gunsmoke
  Daily Patrol is inferred from counters only when the exact merit, score,
  attempt, and attendance combination has a single positive solution. A
  negative result remains unknown until a closing boundary, UID-safe Updates
  kind `8` / action `802001`, or a manual correction establishes it.
- Each contiguous run of Gunsmoke checkpoints is solved independently. A
  missing earlier day therefore cannot suppress exact facts in a later usable
  run, while the unobserved gap remains unknown.
- On the final Saturday, a capture at or after 02:00 finalizes the captured
  weekly Gunsmoke score and allows the solver to reconcile whole-week attempt,
  Login, and Daily Patrol totals across every compatible history. A weekly
  total is exact when every history agrees even if the day containing a
  shortfall remains ambiguous. The 05:00 game-day boundary still controls
  merit, attendance, Daily Patrol, and the week transition; unresolved values
  therefore remain lower bounds.
- If a later transition exceeds the bounded detailed search, already solved
  earlier days remain visible. The cumulative score/high-score attempt floor
  and timestamped Login or Daily Patrol facts are retained even when a broader
  aggregate fallback admits a smaller value.
- The separate Join/Withdraw section combines UID-safe 21917 roster changes,
  exact 21960 Updates timestamps, and manually entered membership history.
  Snapshot-only boundaries are labelled with their observation time instead of
  being presented as exact event times.
- Calculated values retain an evidence state: exact, inferred, ambiguous, or
  manually confirmed.
- Missing daily Gunsmoke score and attempt values stay unknown. Confirmed
  subtotals remain visible as lower bounds, while exact daily/weekly caps are
  displayed without `≥`, `?`, or `-`. Exact values and lower bounds below a
  configured cutoff use the yellow warning color until the displayed minimum
  reaches that cutoff. The user can apply a manual correction when they have
  independent evidence.
- If a Gunsmoke day is completely unobserved, weekly Login and Daily Patrol
  totals also stay unknown unless a timestamped activity fact or manual edit
  independently proves them.

## Member status and membership periods

- UID is the stable member identity; a display name can change over time.
- A custom nickname is editable and immediately becomes the display name in
  Platoon management; the personal note remains private to member details.
- The first observed roster creates an active membership period with an unknown historical
  join time unless the user supplies one.
- A UID added between consecutive snapshots opens a join or rejoin membership period.
- A UID removed between consecutive snapshots closes the active membership period but
  never deletes the member or prior snapshots.
- A returning UID opens a new membership period. All previous join/withdraw periods remain
  available.
- A roster CSV imported after newer captures still contributes historical
  presence spans. The replay creates missing inactive members and inferred
  withdrawal/rejoin periods without replacing manual or exact Updates evidence.
  An observed absence can add or refine the missing inferred withdrawal side of
  an exact open period, after which a later presence becomes a separate rejoin.
  Notes on snapshot-derived periods survive this replay, while their weak
  inferred boundaries can still expand to roster evidence discovered later.
- An individual membership period can be deleted from its editor after a
  destructive-action confirmation. The only remaining period is protected;
  delete the member instead when the complete record should be removed.
- The current member list displays the latest open membership period. A withdrawn member
  displays the latest join and withdrawal times.
- Snapshot differences provide an observed time window. Exact in-game Updates
  events can replace an inferred boundary only when a unique 21917 UID can be
  correlated safely; 21935 contains names but no UID.
- Exact packet-linked boundary fields are immutable while editing. Users can
  add or edit manual membership periods, including historical withdrawn members
  not present in the incremental activity response; deleting an entire period
  requires the separate confirmation flow described below.
- Membership-period numbers are assigned by join date from oldest to newest and are
  recalculated after manual additions or edits.

## Persistence and privacy

Structured snapshots, timestamped activity facts, members, membership periods, events,
weekly notes, and manual corrections are stored in the app's private on-device
database. Raw network traffic is never stored. Export and backup contain only
parsed management data and must be explicitly initiated by the user.

CSV files created for spreadsheet use neutralize string cells beginning with
`=`, `+`, `-`, or `@` (including after leading whitespace), preventing a member
name or note from executing as a spreadsheet formula. Internal retained roster
evidence remains lossless and is not rewritten with that export-only prefix.
The same neutralization is applied to captured roster, activity, Updates, and
formation packet tables before they can be copied or opened in spreadsheet
software.

Untrusted evidence is bounded at every persistence boundary. A roster import
must contain no more than 256 unique UIDs, an activity payload contributes at
most 250 distinct observations, and the database keeps the newest 10,000
activity facts. If a newly selected roster CSV fails parsing, duplicate-UID
validation, ingestion, or chronological reconciliation, its newly retained file
is deleted so a failed import cannot poison every later reconciliation. The
entire selected-file reconciliation runs under one outer SQLite transaction, so
deleting newly retained files after failure also rolls back nested snapshot and
membership writes.
