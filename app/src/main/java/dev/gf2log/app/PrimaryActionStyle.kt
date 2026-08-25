package dev.gf2log.app

import android.widget.Button

fun Button.usePrimaryActionStyle() = useActionRole(ModernUi.ControlRole.PRIMARY)

fun Button.useCaptureActionStyle() = useActionRole(ModernUi.ControlRole.CAPTURE)

fun Button.useFeatureActionStyle() = useActionRole(ModernUi.ControlRole.FEATURE)

fun Button.useSecondaryActionStyle() = useActionRole(ModernUi.ControlRole.SECONDARY)

fun Button.useTertiaryActionStyle() = useActionRole(ModernUi.ControlRole.TERTIARY)

fun Button.useNavigationActionStyle() = useActionRole(ModernUi.ControlRole.NAVIGATION)

fun Button.useSelectorActionStyle() = useActionRole(ModernUi.ControlRole.SELECTOR)

fun Button.useEvidenceActionStyle() = useActionRole(ModernUi.ControlRole.EVIDENCE)

fun Button.useDestructiveActionStyle() = useActionRole(ModernUi.ControlRole.DESTRUCTIVE)

fun Button.useDestructiveTextActionStyle() = useActionRole(ModernUi.ControlRole.DESTRUCTIVE_TEXT)

fun Button.allowCompactMultilineLabel() {
    setTag(R.id.gf2_ui_compact_multiline, true)
    ModernUi.styleButton(this)
}

private fun Button.useActionRole(role: ModernUi.ControlRole) {
    setTag(R.id.gf2_ui_role, role)
    ModernUi.styleButton(this)
}
