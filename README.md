# mobileGF2logger

[![Android](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml)
[![CodeQL](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml)
[![Dependabot](https://img.shields.io/github/issues-pr/1window2/mobileGF2logger/dependencies?label=Dependabot&logo=dependabot)](https://github.com/1window2/mobileGF2logger/pulls?q=is%3Apr+is%3Aopen+author%3Aapp%2Fdependabot)

This is a **Platoon(서클)** member logger for the Android client of [GIRLS' FRONTLINE 2: EXILIUM](https://gf2exilium.sunborngame.com/). It is Android-only; an iOS release is not planned.

mobileGF2logger is a lightweight, non-root app for Platoon masters who want to review member merit data. It uses Android's per-app VPN permission to observe only the selected game, parses supported plaintext server responses entirely on the phone, and never stores raw traffic.

## Features

- Captures and parses the Platoon member response without a computer or root access.
- Offers one-shot Platoon capture that stops automatically after a complete roster is parsed.
- Shows the latest 100 parsed packets in `yy/MM/dd HH:mm:ss` format with fixed-size payload-role tags.
- Displays history times in the Android device timezone while keeping exported CSV timestamps in UTC.
- Provides a gear-shaped payload-options screen for Weapons, Attachments, Common Keys, and Formations; optional history capture is off by default while Platoon Members is always enabled.
- Keeps up to 50 selected packets in a separate saved collection until manually deleted.
- Opens parsed results as a clean table, with access to the complete raw CSV text and clipboard copy.
- Supports selecting and deleting recent or saved history entries.
- Tracks active and withdrawn members, repeat tenures, roster changes, and editable member notes in private on-device storage.
- Builds Sunday-to-Saturday weekly tables around the 05:00 game reset and the
  three-week Gunsmoke Frontline cycle.
- Keeps Standard Week tables compact by showing only Merit, Login, and Daily
  Patrol; Gunsmoke weeks add score and attempt cells.
- Supports separate daily and weekly cut-off points for merit, Gunsmoke score
  and attempts, weekly login days, and weekly Daily Patrol days.
- Sorts members by join date, Weekly Merit, or Total Merit in either direction,
  and preserves custom long-press drag ordering across later weeks.
- Copies snapshot comparisons and weekly CSV data directly to the clipboard,
  exports weekly CSV files, and opens any week through a calendar picker.
- Distinguishes unobserved pre-join activity (`-`) from missed activity (`x`)
  when roster membership changes.
- Recalculates the weekly table from each newly captured roster, including a
  confirmed newcomer's first observed merit.
- Separates immutable packet-derived Join/Withdraw events from deletable manual
  Notes.
- Marks live same-day values as partial lower bounds. When captures are sparse,
  it uses Total Merit to preserve a supported interval total and visibly marks
  plausible `90`/`50`/`0` daily allocations as approximate rather than
  presenting them as measured facts.
- Keeps missing Gunsmoke days unknown instead of inventing daily score or
  attempts, while using captured counters to preserve the strongest supported
  Total-column value.
- Provides a caution-gated manual editor for daily merit, Gunsmoke score and
  attempts, Login, and Daily Patrol when captured data needs correction.
- Supports English and Korean, using the Android device timezone by default.
- Exports and imports explicit Platoon-management backups without storing raw traffic.
- Uses a GF2-inspired orange, off-white, and black Platoon emblem as its launcher icon.
- Creates exportable UTF-8 CSV files with this column order:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## Use

1. Install the ARM64 APK on Android 8.0 or newer.
2. Open **GF2logger**, confirm the game package, and select **Capture one Platoon roster**.
3. Approve Android's VPN prompt, then open the game.
4. Enter **Platoon(서클)** and open **Members(멤버)**.
5. Return to GF2logger to review packet history or open **Platoon management**.

The app keeps parsed history and generated CSV files in private on-device storage. It does not bypass TLS, certificate pinning, or anti-cheat systems, and it does not modify game traffic.

Licensed under GPL-3.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for bundled components.
