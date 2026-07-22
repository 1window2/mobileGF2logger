# mobileGF2logger

[![Android](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml)
[![CodeQL](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml)
[![Dependabot](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fapi.github.com%2Fsearch%2Fissues%3Fq%3Drepo%253A1window2%252FmobileGF2logger%2520is%253Apr%2520is%253Aopen%2520author%253Aapp%252Fdependabot&query=%24.total_count&label=Dependabot&suffix=%20open&color=yellow)](https://github.com/1window2/mobileGF2logger/pulls?q=is%3Apr+is%3Aopen+author%3Aapp%2Fdependabot)

This is a **Platoon(서클)** member logger for the Android client of [GIRLS' FRONTLINE 2: EXILIUM](https://gf2exilium.sunborngame.com/). It is Android-only; an iOS release is not planned.

mobileGF2logger is a lightweight, non-root app for Platoon masters who want to review member merit data. It uses Android's per-app VPN permission to observe only the selected game, parses supported plaintext server responses entirely on the phone, and never stores raw traffic.

## Features

- Captures and parses the Platoon member response without a computer or root access.
- Shows the latest 100 parsed packets in `yy/MM/dd HH:mm:ss` format with fixed-size payload-role tags.
- Displays history times in the Android device timezone while keeping exported CSV timestamps in UTC.
- Provides a gear-shaped payload-options screen for Weapons, Attachments, Common Keys, and Formations; optional history capture is off by default while Platoon Members is always enabled.
- Keeps up to 50 selected packets in a separate saved collection until manually deleted.
- Opens parsed results as a clean table, with access to the complete raw CSV text and clipboard copy.
- Supports selecting and deleting recent or saved history entries.
- Creates exportable UTF-8 CSV files with this column order:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## Use

1. Install the ARM64 APK on Android 8.0 or newer.
2. Open **GF2logger**, confirm the game package, and select **Prepare capture**.
3. Approve Android's VPN prompt, then open the game.
4. Enter **Platoon(서클)** and open **Members(멤버)**.
5. Return to GF2logger to view, copy, delete, or export the captured result.

The app keeps parsed history and generated CSV files in private on-device storage. It does not bypass TLS, certificate pinning, or anti-cheat systems, and it does not modify game traffic.

Licensed under GPL-3.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for bundled components.
