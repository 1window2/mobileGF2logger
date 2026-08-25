# mobileGF2logger — GIRLS' FRONTLINE 2 (GF2) Platoon Manager

[![Android](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml)
[![CodeQL](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml)
[![Dependabot](https://img.shields.io/github/issues-pr/1window2/mobileGF2logger/dependencies?label=Dependabot&logo=dependabot)](https://github.com/1window2/mobileGF2logger/pulls?q=is%3Apr+is%3Aopen+author%3Aapp%2Fdependabot)

한국어로 된 설명은 [여기](README_KR.md)에서 보실 수 있습니다.

This is a **GIRLS' FRONTLINE 2: EXILIUM (GF2) Platoon/서클 management and logging companion** for the [Android game client](https://gf2exilium.sunborngame.com/). It is Android-only; an iOS release is not planned.

mobileGF2logger is a lightweight, non-root GF2 logger for Platoon masters. It
turns supported roster and activity responses into member history and weekly
task tables entirely on the phone. Android's per-app VPN permission limits
capture to the fixed HaoPlay and Darkwinter game packages, and raw traffic is
never stored.

## Features

- Captures the mandatory Platoon Profile (`21905`), Members (`21917`), Activity (`21935`), and Updates (`21960`) responses without a computer or root access.
- Automatically separates detected Platoons by supported Android client,
  selected server region, and authoritative Platoon ID; switch the active
  isolated profile from Home, Platoon, Weekly, or Settings.
- Tracks active and withdrawn members, non-overlapping repeat membership periods, exact Updates timestamps, editable nicknames, and private notes.
- Builds Sunday-to-Saturday Standard or Gunsmoke Frontline weekly tables around the selected server's daily reset, with cut-off points and manual correction for missing data.
- Offers One-time Capture that tracks the four useful Platoon payloads and stops automatically when the checklist is complete.
- Explains every weekly cell on tap and summarizes missing or uncertain evidence in an Evidence Health panel.
- Keeps up to 15 complete automatic revisions per weekly table so an earlier projection and its displayed member context can be previewed and restored after an accidental import.
- Recovers interrupted imports before previewing roster CSV impact and keeps an automatic one-level checkpoint for undo.
- Saves or shares a weekly PNG with opt-in controls for names, UIDs, and private notes.
- Can send a validated original CSV to an optional user-owned Discord incoming webhook after confirmation.
- Stores the latest 100 parsed packets and up to 50 saved packets, with table and raw views, copy, export, selection, and deletion.
- Supports member sorting, persistent drag ordering, snapshot comparison, single-week and all-week CSV export, and profile-aware `.gf2backup` export/restore that leaves other Platoons unchanged.
- Keeps a newly detected Platoon's packets in bounded memory until the user confirms one of the verified client's compatible servers; unconfirmed data is discarded on force-stop or process death.
- Provides profile management for correcting server metadata without moving data and for deleting one isolated Platoon behind two confirmations and an exact-name check.
- Guides first-time users through Main, Settings, Platoon management, weekly controls, and parsed-packet pages, with a persistent English/Korean selector and Skip action.
- Supports English and Korean, System/Light/Dark themes, and the six known
  Darkwinter/HaoPlay server-region reset presets converted to the phone timezone.
  Selecting a detected Platoon automatically follows that profile's saved region.
- Registers the HaoPlay (`com.haoplay.game.and.exilium`) and Darkwinter
  (`com.Sunborn.SnqxExilium.Glo`) Android clients as separate VPN targets.
- Creates UTF-8 Platoon-member CSV files with this column order:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## Use

1. Install the ARM64 APK on Android 8.0 or newer.
2. Open **GF2logger**, confirm both supported clients are installed as needed, and select **One-time Capture**.
3. Approve Android's VPN prompt, then open the game.
4. Enter **Platoon(서클)** and open **Updates(동향)** and **Members(멤버)**.
5. Return to GF2logger to review the captured packets and Platoon data.

On Android 10 and newer, Android identifies which supported game owns each
captured connection. Android 8–9 can safely attribute management data only when
exactly one supported client is installed; with both clients installed,
unattributed management payloads are deliberately not imported. Because the
plaintext protocol does not expose a trustworthy server identifier, select the
correct HaoPlay and Darkwinter server in Settings before first capture.

The app keeps parsed history, management data, and generated CSV files in private on-device storage. It does not bypass TLS, certificate pinning, or anti-cheat systems, and it does not modify game traffic. Server responses may contain only recent incremental history, so older missing membership records can be entered manually.

## Reference

mobileGF2logger was inspired by [blead/gfl2logger](https://github.com/blead/gfl2logger),
a GF2 logger for the Windows client. This project is an independent Android
implementation tailored to on-device Platoon management.
