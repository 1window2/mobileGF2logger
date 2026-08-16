# GF2logger product contract

## Product and platform

GF2logger is a native Android companion for GIRLS' FRONTLINE 2 platoon leaders.
It observes one explicitly selected game package through Android's consent-based
`VpnService`, parses only recognized plaintext GF2 payloads, and turns that
evidence into private on-device packet history, platoon membership records, and
weekly reports. It does not decrypt TLS, retain raw network packets, or run a
remote collector.

## Primary user and outcome

The primary user is a platoon leader checking the app during or shortly after a
game session. Their core job is to capture or import reliable evidence and see
it reflected automatically in the roster and weekly table without manually
reconciling packet details.

The interface must answer three questions quickly:

1. Is capture ready, running, complete, or stopped?
2. Where do I manage the platoon and review the current week?
3. What recent evidence is available, and what can I safely import or export?

## Product positioning

GF2logger is an evidence-aware platoon operations tool, not a general packet
analyzer. Its value is the bounded pipeline from recognized payloads to stable
membership history and weekly reporting, with explicit privacy boundaries and
recoverable user-controlled backup and export.

## Functional and architectural constraints

- Keep the existing Kotlin/Android Views architecture. Do not introduce a
  parallel UI framework or move repository, parser, capture, or report policy
  into Activities.
- Preserve English and Korean, System/Light/Dark appearance, Android Back
  behavior, private app storage, and explicit user-driven share/export.
- Keep the three primary destinations clear: Home, Platoon, and Weekly.
- Settings is a secondary destination reached from Home and returns with normal
  Android navigation.
- The UI may reorganize real functionality, but must not invent mockup-only
  settings or imply unsupported capture behavior.
- All actionable controls retain at least a 48dp touch target and meaningful
  accessibility labels. Motion is restrained and respects system animation
  settings.

## Brand and visual ground truth

The approved three-screen composition at
`C:/Users/alex1/AppData/Local/Temp/codex-clipboard-2fc2ac39-8f45-4c66-a753-4cf5ec1b2d44.png`
is the binding visual direction for the v2.3.0 frontend:

- warm paper-like off-white and soft charcoal are the dominant fields;
- muted orange is the sole brand and primary-action accent;
- green communicates capture readiness or success, and red is reserved for
  stop, destructive, or failed states;
- layouts are compact, status-led, flat, and separated with hairlines or tonal
  surfaces instead of nested oversized cards;
- Home uses a status dashboard, key Platoon/Weekly shortcuts, concise utilities,
  recent evidence, and a three-destination bottom bar;
- Settings uses a compact top bar and grouped list rows;
- onboarding uses an editorial dark composition with the emblem, concise
  feature guidance, visible progress, top-right Skip, and bottom navigation.

The shipped emblem remains the existing launcher artwork at
`app/src/main/res/drawable-nodpi/ic_launcher_art.png`.

## Design principles

1. Evidence truth before decoration.
2. Compact scanability without sacrificing touch targets.
3. Privacy and capture state are explicit in words and symbols.
4. Real actions receive hierarchy; secondary utilities remain quiet.
5. English and Korean layouts are first-class and are reviewed on device.

## Repository evidence

- `docs/ARCHITECTURE.md` defines capture, parser, management, and presentation
  boundaries.
- `docs/UI_DESIGN_SYSTEM.md` defines semantic control roles and accessibility
  gates.
- The approved composition above defines the current visual contract.
