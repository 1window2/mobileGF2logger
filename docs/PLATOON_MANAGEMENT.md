# Platoon management model

This document defines the management rules implemented by GF2logger. The
reference workbook is the leader-maintained `부엉이 관리현황` spreadsheet.
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

The calculation is accepted when the remaining merit is one of `0`, `40`,
`50`, or `90`. A unique match is reported as inferred. Multiple matches remain
ambiguous and are shown for manual confirmation; the app does not fabricate an
exact count.

Counter resets are handled by treating a negative delta as the current counter
value. Daily activity is derived from Total Merit because Merit This Week
resets on Monday while a Gunsmoke week begins on Sunday.

## Snapshots and weekly tables

- Every Platoon Members payload is stored as a structured snapshot keyed by its
  capture instant.
- Every supplied Platoon Activity entry is stored as an immutable timestamped
  fact. Action `802001` supplies exact Daily Patrol evidence.
- Platoon Updates payload 21960 supplies UID-safe exact Join, Withdraw, and
  Remove timestamps.
- Non-Gunsmoke weeks show daily Total Merit changes, inferred attendance,
  activity-backed Daily Patrol completion, and notes.
- Gunsmoke weeks additionally show daily score changes, inferred participation
  counts, cumulative Gunsmoke score, participation totals, and rankings.
- The separate Join/Withdraw section combines UID-safe 21917 roster changes,
  exact 21960 Updates timestamps, and manually entered membership history.
  Snapshot-only boundaries are labelled with their observation time instead of
  being presented as exact event times.
- Calculated values retain an evidence state: exact, inferred, ambiguous, or
  manually confirmed.
- Missing daily Gunsmoke score and attempt values stay unknown. Captured weekly
  totals remain visible and the user can apply a manual correction when they
  have independent evidence.
- If a Gunsmoke day is completely unobserved, weekly Login and Daily Patrol
  totals also stay unknown unless a timestamped activity fact or manual edit
  independently proves them.

## Member status and tenure

- UID is the stable member identity; a display name can change over time.
- A custom nickname is editable and immediately becomes the display name in
  Platoon management; the personal note remains private to member details.
- The first observed roster creates an active tenure with an unknown historical
  join time unless the user supplies one.
- A UID added between consecutive snapshots opens a join or rejoin tenure.
- A UID removed between consecutive snapshots closes the active tenure but
  never deletes the member or prior snapshots.
- A returning UID opens a new tenure. All previous join/withdraw periods remain
  available.
- The current member list displays the latest open tenure. A withdrawn member
  displays the latest join and withdrawal times.
- Snapshot differences provide an observed time window. Exact in-game Updates
  events can replace an inferred boundary only when a unique 21917 UID can be
  correlated safely; 21935 contains names but no UID.
- Exact packet-linked boundaries are non-deletable. Users can add or edit
  manual tenures, including historical withdrawn members not present in the
  incremental activity response.
- Tenure numbers are assigned by join date from oldest to newest and are
  recalculated after manual additions or edits.

## Persistence and privacy

Structured snapshots, timestamped activity facts, members, tenures, events,
weekly notes, and manual corrections are stored in the app's private on-device
database. Raw network traffic is never stored. Export and backup contain only
parsed management data and must be explicitly initiated by the user.
