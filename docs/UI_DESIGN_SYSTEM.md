# UI design system

GF2logger uses a warm, minimal Android presentation layer for dense operational
data. Domain behavior remains outside the UI: Activities compose views and call
repository or policy APIs, while `ModernUi` owns reusable presentation roles.

## Visual language

- The dominant colors are warm off-white and charcoal, never absolute white or
  black. Light and dark resources are designed as a pair rather than inverted.
- Soft orange is the sole brand accent. It identifies the primary action and
  key routes such as Platoon management and the weekly table.
- Green communicates capture/readiness or confirmed success. Red is reserved
  for destructive or failed states. Every state also has text or a symbol, so
  color is never the only signal.
- Surfaces are flat and tonal, with zero elevation and restrained 12-16dp
  corner radii only where they communicate a real group or touch target.
  Shadows, gradients, decorative blur, nested cards, and borders around every
  row are not part of the system.
- Bold type is limited to screen, section, and item headings. Body copy uses
  the platform sans-serif; table-like data may use monospaced figures.

## Control roles

`ModernUi.ControlRole` is the source of truth for action hierarchy:

| Role | Use |
| --- | --- |
| Primary | The single commitment action for the current screen |
| Capture | Start or prepare continuous capture; green state treatment |
| Feature | Key navigation to Platoon management or the weekly table; soft orange surface |
| Evidence | Compact date/time action in a packet-history row |
| Secondary | Ordinary bounded action on the current screen |
| Tertiary | Low-emphasis action such as Back, Skip, or a week arrow |
| Navigation | Neutral full-row navigation or selection |
| Destructive | Confirmed destructive operation |
| Destructive text | Less prominent destructive option separated from primary work |

Buttons keep a 48dp touch target but use an inset 40dp visible surface. A
multi-line destination uses `ModernUi.actionRow`: a 56dp row with a title,
optional detail, and chevron instead of a tall button containing paragraphs.
Do not encode `CheckBox` or `RadioButton` as generic buttons.

Language and theme selectors are a single 56dp rail, not a row of adjacent
buttons. The rail has a 16dp radius and 4dp padding on all four sides. One 48dp,
12dp-radius accent thumb moves between equal-width native radio choices. This
concentric geometry keeps the horizontal and vertical inset identical and
prevents selected and unselected options from developing mismatched seams.

## Layout and accessibility

- Spacing follows a 4dp/8dp rhythm. Neighboring bounded shapes retain an 8dp
  visual gap; compact packet evidence may use a 32dp visible surface inside a
  48dp touch target.
- Repeated member surfaces use an 8dp vertical gap. Search and paired filter
  controls use the same 8dp rhythm, split symmetrically where two controls share
  a row.
- The approved v2.3.0 visual contract is recorded in `PRODUCT.md` and
  `.interface-design/system.md`. Home is status-led, Settings is a grouped flat
  list, and onboarding is a five-step editorial dark surface.
- Screens use three interaction levels: a bounded primary workspace, distinctive
  feature shortcuts, and quiet utility rows. Do not render every action as the
  same full-width button.
- Home, Platoon management, and the weekly table share a persistent three-item
  bottom navigation bar. Settings remains a secondary screen with a normal back
  action; deeper detail and edit screens continue to use Android Back.
- Compact controls may look smaller, but their hit area remains at least 48dp.
- Text wraps when it carries meaning; only short button labels may ellipsize.
- Icon-only controls require an accessible name. Decorative icons beside text
  are hidden from the accessibility tree.
- Pressed and disabled states are explicit. Destructive operations use a
  confirmation dialog, and async work disables duplicate submission.
- Motion is limited to short transform/opacity transitions and is skipped when
  Android animations are disabled.
- Light and dark screenshots must include status/navigation bars, narrow-screen
  label checks, and the dense packet, weekly, and membership views.

## Review checklist

Before adding or changing a screen:

1. Identify the one primary action and assign every other control a lower role.
2. Use semantic resource colors; do not add raw RGB values in Activities.
3. Verify English and Korean labels at the supported system font scales.
4. Check a dark and light physical-device render, including system bar contrast.
5. Confirm that presentation code does not acquire SQL, parser, inference, or
   capture-policy responsibilities.
