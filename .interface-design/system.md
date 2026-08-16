# GF2logger interface system

## Direction

Use the approved three-screen composition recorded in `PRODUCT.md` as the
visual ground truth. The product should feel like a compact field instrument:
quiet, legible, evidence-led, and unmistakably Android rather than a generic
card stack.

## Layout grammar

- Use a 4dp base grid. Default screen inset is 16dp; related copy is separated
  by 4dp or 8dp; sections are separated by 16dp or 24dp.
- Keep every touch target at least 48dp high, while using inset fills and
  hairlines so the visible control can remain compact.
- Home is status-led: app bar, capture status, two key destinations, quiet
  utilities, recent evidence, then the persistent Home/Platoon/Weekly bar.
- Settings uses a compact back app bar followed by grouped list rows. Do not
  turn every setting into a card.
- Onboarding uses a dark editorial field: top actions, emblem, title and short
  explanation, progress, feature list, bottom Previous/Next actions, step label.
- Preserve ordinary Android Back behavior. Bottom navigation is reserved for
  Home, Platoon, and Weekly.

## Color roles

- Background: warm off-white in light mode; soft near-black charcoal in dark.
- Surface: a subtle one-step tonal lift from the background.
- Primary text: warm ink/ivory. Secondary text is lower-contrast but AA legible.
- Brand/primary: muted orange, used sparingly for the selected destination,
  commitment actions, and the emblem's star.
- Success/capture: desaturated green with explicit status text or an icon.
- Stop/destructive/failure: softened red with explicit text or an icon.
- Avoid absolute black/white, gradients, glass effects, decorative blur, and
  deep shadows.

## Type

- Use the Android system sans family so English and Korean share consistent
  metrics and device font scaling.
- Screen title: 22-24sp medium/bold.
- Section title: 15-17sp medium.
- Body/action label: 14-16sp regular/medium.
- Supporting text and metadata: 12-13sp regular.
- Keep centered copy to onboarding headlines and short descriptions; operational
  and settings copy is start-aligned.

## Component rules

- Header icon actions: 48dp hit area with a quiet tonal or transparent surface.
- Capture status: a bounded outlined/tonal panel with a small label, prominent
  state, concise detail, and distinct Start/One-time/Stop actions.
- Feature destinations: paired compact rows/cards with icon, title, supporting
  text, and chevron. Orange marks their importance without filling the screen.
- Utility/settings rows: 52-60dp, title plus optional supporting line, trailing
  chevron or native checkbox/switch. Use dividers or common-region spacing.
- Bottom navigation: three equal 56-64dp destinations; selected state uses
  orange icon/label and unselected destinations remain neutral.
- Onboarding: a compact language action remains available because locale is a
  required first-use decision; Skip is always visible.

## Accessibility and state

- Controls expose native semantics and readable content descriptions.
- Color never carries state alone. Pair capture, selection, warning, and error
  colors with text and/or icons.
- Pressed, disabled, selected, and busy states must remain visually distinct.
- Review English and Korean on the physical phone, including onboarding pages,
  light Settings, dark Home, system bars, and enlarged font behavior.
