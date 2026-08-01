# mobileGF2logger

[![Android](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml)
[![CodeQL](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml)
[![Dependabot](https://img.shields.io/github/issues-pr/1window2/mobileGF2logger/dependencies?label=Dependabot&logo=dependabot)](https://github.com/1window2/mobileGF2logger/pulls?q=is%3Apr+is%3Aopen+author%3Aapp%2Fdependabot)

한국어로 된 설명은 [여기](README_KR.md)에서 보실 수 있습니다.

This is a **Platoon(서클)** management tool for the Android client of [GIRLS' FRONTLINE 2: EXILIUM](https://gf2exilium.sunborngame.com/). It is Android-only; an iOS release is not planned.

mobileGF2logger is a lightweight, non-root app for Platoon masters. It uses Android's per-app VPN permission to parse supported plaintext server responses entirely on the phone without storing raw traffic.

## Features

- Captures the mandatory Members (`21917`), Activity (`21935`), and Updates (`21960`) responses without a computer or root access.
- Tracks active and withdrawn members, repeat tenures, exact Updates timestamps, editable nicknames, and private notes.
- Builds Sunday-to-Saturday Standard or Gunsmoke Frontline weekly tables around the 05:00 game reset, with cut-off points and manual correction for missing data.
- Stores the latest 100 parsed packets and up to 50 saved packets, with table and raw views, copy, export, selection, and deletion.
- Supports member sorting, persistent drag ordering, snapshot comparison, single-week and all-week CSV export, and complete `.gf2backup` export/restore.
- Supports English and Korean and uses the Android device timezone for display.
- Creates UTF-8 Platoon-member CSV files with this column order:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## Use

1. Install the ARM64 APK on Android 8.0 or newer.
2. Open **GF2logger**, confirm the game package, and select **Capture one Platoon roster**.
3. Approve Android's VPN prompt, then open the game.
4. Enter **Platoon(서클)** and open **Updates(동향)** and **Members(멤버)**.
5. Return to GF2logger to review the captured packets and Platoon data.

The app keeps parsed history, management data, and generated CSV files in private on-device storage. It does not bypass TLS, certificate pinning, or anti-cheat systems, and it does not modify game traffic. Server responses may contain only recent incremental history, so older missing membership records can be entered manually.
