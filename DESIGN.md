---
name: GF2logger
description: A compact evidence-led field instrument for GF2 platoon leaders.
colors:
  brand-orange: "#D7824B"
  warm-paper: "#F7F4EF"
  paper-surface: "#FFFCF8"
  warm-ink: "#25231F"
  muted-ink: "#6C665F"
  soft-charcoal: "#20211F"
  charcoal-surface: "#292A27"
  warm-ivory: "#F5F0E8"
  capture-green: "#4B7955"
  stop-red: "#A85258"
typography:
  headline:
    fontFamily: "sans-serif"
    fontSize: "24sp"
    fontWeight: 700
    lineHeight: 1.2
  title:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: 1.4
  label:
    fontFamily: "sans-serif"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: 1.3
rounded:
  control: "14dp"
  panel: "16dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "16dp"
  lg: "24dp"
components:
  button-primary:
    backgroundColor: "{colors.brand-orange}"
    textColor: "{colors.warm-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    height: "48dp"
  list-row:
    backgroundColor: "{colors.paper-surface}"
    textColor: "{colors.warm-ink}"
    typography: "{typography.body}"
    height: "60dp"
  bottom-navigation:
    backgroundColor: "{colors.paper-surface}"
    textColor: "{colors.muted-ink}"
    typography: "{typography.label}"
    height: "64dp"
---

# Design System: GF2logger

## Overview

**Creative North Star: "The Evidence Field Instrument"**

GF2logger should feel like a quiet, dependable instrument used during and after a game session. Its hierarchy is operational rather than decorative: capture state comes first, Platoon and Weekly are unmistakable destinations, and data utilities stay compact and calm.

Warm paper and soft charcoal replace sterile white and absolute black. Muted orange is rare enough to remain meaningful, while green and red communicate capture and destructive states only when paired with text or an icon.

**Key Characteristics:**

- Compact, status-led Android layouts.
- Warm neutral fields with one restrained brand accent.
- Flat tonal grouping, hairlines, and minimal shadow.
- Equal care for English, Korean, and enlarged system text.

## Colors

The palette is warm, low-glare, and predominantly neutral; semantic color is never decorative.

### Primary

- **Muted Signal Orange:** The sole brand and commitment-action color, used for the selected destination, primary actions, progress, and the emblem star.

### Neutral

- **Warm Paper and Paper Surface:** Light-mode canvas and the single tonal lift used for grouped controls.
- **Warm Ink and Muted Ink:** Primary and supporting copy on light surfaces.
- **Soft Charcoal and Charcoal Surface:** Dark-mode canvas and its one-step tonal lift.
- **Warm Ivory:** Primary copy on dark surfaces.

### Tertiary

- **Capture Green:** Readiness and successful capture state, always reinforced by copy or an icon.
- **Stop Red:** Stop, destructive, failed, or dangerous actions, always reinforced by copy or an icon.

**The One Accent Rule.** Orange marks brand, selection, or commitment; it does not fill ordinary utility rows.

**The State Is a Sentence Rule.** Green, red, and warning tones never carry meaning alone.

## Typography

**Display Font:** Android system sans
**Body Font:** Android system sans

**Character:** Neutral system typography keeps Korean and English metrics predictable and follows the user's Android font scale without introducing a separate font dependency.

### Hierarchy

- **Headline** (bold, 22-24sp): Screen and onboarding titles.
- **Title** (medium/bold, 15-17sp): Section headings, destination labels, and prominent states.
- **Body** (regular/medium, 14-16sp): Actions and operational explanations.
- **Label** (regular/medium, 11-13sp): Navigation, metadata, and supporting text.

**The System Scale Rule.** Never replace scalable text with text baked into an image.

## Layout

Use a 4dp grid, a 16dp default screen inset, and 16-24dp between sections. Every actionable region is at least 48dp high even when its visible fill is inset. Home remains status-led; Settings uses grouped rows; onboarding keeps fixed top controls and bottom navigation around a flexible central explanation. Home, Platoon, and Weekly alone use the three-item bottom navigation.

## Elevation & Depth

The system is flat by default. Depth comes from a one-step tonal surface lift, 1dp hairlines, and spacing rather than deep shadows or nested cards.

**The Flat Instrument Rule.** A surface must earn separation through function before receiving a container.

## Shapes

Controls use gently curved 14dp corners and bounded panels use 16dp corners. Hairline dividers and restrained outlines preserve the compact instrument-like character. Avoid pills unless the control is genuinely binary or segmented.

## Components

### Buttons

- **Shape:** Gently curved controls with a minimum 48dp target.
- **Primary:** Muted orange fill with warm dark text for commitment actions.
- **Secondary:** Quiet tonal or outlined treatment for reversible actions.
- **Destructive:** Soft red treatment with an explicit label; disabled states remain visually distinct.

### Cards / Containers

- **Corner Style:** Bounded panels use 16dp corners.
- **Background:** One tonal step from the current theme background.
- **Shadow Strategy:** No default shadow; use spacing and outline.
- **Internal Padding:** 8-16dp according to content density.

### Inputs / Fields

- **Style:** Tonal or outlined field with a 48dp minimum target.
- **Focus:** Clear selected/checked semantics plus the current theme's accent treatment.
- **Error / Disabled:** State remains readable without relying on opacity alone.

### Navigation

Home, Platoon, and Weekly share a 64dp bottom bar. Each destination has a 24dp icon and label; the active destination uses orange while inactive destinations stay neutral. Settings is reached from Home and follows ordinary Android Back behavior.

### Capture Status Panel

Capture state, two compact read-only supported-client package IDs, their concise help popover, and Start/One-time/Stop actions form one operational group. Start actions become unavailable while capture is preparing or active; Stop becomes unavailable while stopped.

### Onboarding

Five dark editorial pages present the emblem, one concise topic, progress, three feature statements, persistent language/Skip controls, and Previous/Next actions. Onboarding appears only on first launch, but the debug build exposes a non-production preview alias for visual verification.

## Do's and Don'ts

### Do:

- **Do** keep capture state and next actions visible without scrolling on the primary screen.
- **Do** preserve 48dp targets, native checked semantics, and Android Back behavior.
- **Do** verify English, Korean, light/dark themes, system bars, and enlarged text on a physical device.
- **Do** use orange sparingly for primary action and selection.

### Don't:

- **Don't** use absolute black, stark white, gradients, glass effects, decorative blur, or deep shadows.
- **Don't** turn every row into a large card or invent mockup-only settings.
- **Don't** use color as the only status signal.
- **Don't** move parser, capture, repository, or report policy into Activities while changing presentation.
