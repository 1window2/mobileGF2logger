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

## Building blocks

Screens are composed in code from the shared `dev.gf2log.app.ui` package. An
Activity chooses components and roles; it never defines colors, dp math, or
one-off drawables.

| Component | Use |
| --- | --- |
| `Screen` | Edge-to-edge shell: top bar, replaceable body, optional Home/Platoon/Weekly bar. Applies status, navigation, cutout, and keyboard insets once. |
| `TopBar` | 56dp bar with optional back/close navigation, title and subtitle, up to two icon actions, and an overflow menu for secondary utilities. |
| `Controls` | Button roles, checkbox/switch tints, and the segmented rail. |
| `Views` | Text roles, labeled fields, grouped navigation rows, switch rows, panels, badges. |
| `StateViews` | One loading, empty, and failure composition for every screen. |
| `Dialogs` / `Feedback` | Styled dialogs with a red treatment for destructive confirmations; a single replacing toast. |
| `BackgroundWork` | Serialized off-main work bound to the screen lifecycle; keyed requests supersede stale ones. |
| `SingleViewAdapter` | Hosts headers, footers, and state views inside one `RecyclerView` without recycling them. |

## Control roles

`Controls.Role` is the source of truth for action hierarchy:

| Role | Use |
| --- | --- |
| Primary | The single commitment action of a screen or panel |
| Capture | Start continuous capture; green readiness treatment |
| Secondary | Ordinary bounded action |
| Tertiary | Low-emphasis inline action such as Select, Show all, or This week |
| Destructive | Confirmed destructive operation, such as Stop |
| Destructive text | Less prominent destructive option inside a selection bar |

Buttons keep a 48dp touch target around a 40dp visible surface. Multi-line
destinations use grouped navigation rows with a title, supporting line, and
chevron instead of tall buttons. Secondary utilities live in the top-bar
overflow menu rather than as stacked full-width buttons.

Choices use one 56dp rail with a 16dp radius and 4dp inset; a 48dp, 12dp-radius
accent thumb moves between equal-width native radio buttons. Motion is skipped
when Android animations are disabled.

## Interaction patterns

- Lists enter selection mode on long-press or from the overflow menu. The top
  bar becomes a contextual bar (close, count, actions) and Back exits selection.
- Edit modes (the weekly table) replace the top bar with close and save actions,
  highlight changed cells, and confirm before discarding unsaved changes.
- Every repository, file, and network call runs through `BackgroundWork`; the
  screen shows a loading, empty, or failure state instead of blocking input.
- Text fields carry stable view IDs and Activities save transient choices, so
  rotation, theme or language changes, and large-screen resizing keep drafts.
- Document pickers use the Activity Result API. Exports regenerate their content
  from the repository when the destination returns rather than holding it in
  memory across process death.

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
- Content draws edge to edge; system-bar and keyboard insets are applied by
  `Screen`, so no screen hides content under the status or navigation bar.
- Text wraps when it carries meaning; only short button labels may ellipsize.
- Icon-only controls require an accessible name and show it as a tooltip.
  Decorative icons beside text are hidden from the accessibility tree.
- Composite rows expose one button with a combined description; validation
  errors and live status use polite accessibility live regions.
- Pressed and disabled states are explicit. Destructive operations use a
  confirmation dialog, and async work disables duplicate submission.
- Motion is limited to short transform/opacity transitions and is skipped when
  Android animations are disabled.
- Light and dark screenshots must include status/navigation bars, narrow-screen
  label checks, and the dense packet, weekly, and membership views.

## Review checklist

Before adding or changing a screen:

1. Identify the one primary action and assign every other control a lower role.
2. Use `ui` components and semantic resource colors; do not add raw RGB values,
   `GradientDrawable`s, or dp math in Activities.
3. Verify English and Korean labels at the supported system font scales.
4. Check a dark and light physical-device render, including system bar contrast.
5. Confirm that presentation code does not acquire SQL, parser, inference, or
   capture-policy responsibilities.
