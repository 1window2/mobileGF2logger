package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.app.settings.CapturePreferences
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.capture.CaptureDiagnosticsStore
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.management.BackupFileName
import dev.gf2log.app.management.InvalidBackupException
import dev.gf2log.app.management.PlatoonBackupManager
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.discord.DiscordWebhookSecretStore
import dev.gf2log.protocol.Gfl2PayloadDecoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.concurrent.Executors
import dev.gf2log.protocol.PayloadCatalog

class OptionsActivity : LocalizedActivity() {
    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileIoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2FullBackup")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        title = getString(R.string.payload_options)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
    }

    override fun onDestroy() {
        fileIoExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Uses the platform document picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_FULL_BACKUP_EXPORT -> {
                val scope = requireActiveScope() ?: return
                runBackupOperation(
                    successMessage = R.string.full_backup_exported,
                    failureMessage = { R.string.full_backup_export_failed },
                ) {
                    val output = TrustedExportDestination.openOutputStream(contentResolver, uri)
                        ?: error("Document provider did not open an output stream")
                    output.use { PlatoonBackupManager(this, scope).exportFull(it) }
                }
            }
            REQUEST_FULL_BACKUP_RESTORE -> {
                if (!hasBackupExtension(uri)) {
                    showBackupMessage(R.string.invalid_full_backup)
                    return
                }
                runBackupOperation(
                    successMessage = R.string.full_backup_restored,
                    failureMessage = ::fullBackupRestoreFailureMessage,
                ) {
                    val input = TrustedImportSource.openInputStream(contentResolver, uri)
                        ?: error("Document provider did not open an input stream")
                    input.use { PlatoonBackupManager.restoreSelected(this, it, complete = true) }
                }
            }
        }
    }

    private fun buildContentView(): ScrollView {
        val spacing = dp(16)
        val preferences = PayloadHistoryPreferences(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, dp(8), spacing, spacing)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    contentDescription = getString(R.string.back)
                    useModernIconStyle()
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    setOnClickListener { finish() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) })
                addView(TextView(context).apply {
                    text = getString(R.string.payload_options)
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWidth())
            addView(
                PlatoonProfileSelector.controls(this@OptionsActivity),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )

            addView(TextView(context).apply {
                text = getString(R.string.language)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.settings_language_detail)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(6))
            }, matchWidth())
            addView(ModernUi.segmentedControl(
                context = context,
                options = listOf(
                    LanguagePreferences.KOREAN to getString(R.string.language_korean_native),
                    LanguagePreferences.DEFAULT_LANGUAGE to getString(R.string.language_english),
                ),
                selectedValue = LanguagePreferences.get(context),
                onSelected = ::changeLanguage,
            ), matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.appearance)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(2))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.settings_appearance_detail)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(6))
            }, matchWidth())
            addView(ModernUi.segmentedControl(
                context = context,
                options = listOf(
                    ThemePreferences.SYSTEM to getString(R.string.theme_system),
                    ThemePreferences.LIGHT to getString(R.string.theme_light),
                    ThemePreferences.DARK to getString(R.string.theme_dark),
                ),
                selectedValue = ThemePreferences.get(context),
                onSelected = ::changeTheme,
            ), matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.daily_reset_time)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(2))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.settings_daily_reset_detail)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(6))
            }, matchWidth())
            val resetScope = profileBinding.scope
            val resetRegion = resetScope?.let { GameTimeZonePreferences.region(context, it.storageId) }
            addView(ModernUi.listRow(
                context = context,
                title = getString(R.string.server_region),
                detail = if (resetRegion == null) {
                    getString(R.string.no_platoon_detected)
                } else {
                    resetSummary(
                        resetRegion,
                        GameTimeZonePreferences.isAutomatic(context, resetScope.storageId),
                    )
                },
                icon = R.drawable.ic_calendar,
                onClick = {
                    if (resetScope == null) {
                        TransientMessage.show(context, R.string.no_platoon_detected_detail)
                    } else {
                        chooseGameServerRegion()
                    }
                },
            ), matchWidth())
            addView(ModernUi.listRow(
                context = context,
                title = getString(R.string.haoplay_capture_region),
                detail = clientRegionLabel(SupportedGamePackages.HAOPLAY),
                icon = R.drawable.ic_group,
                onClick = { chooseClientServerRegion(SupportedGamePackages.HAOPLAY) },
            ), matchWidth())
            addView(ModernUi.listRow(
                context = context,
                title = getString(R.string.darkwinter_capture_region),
                detail = clientRegionLabel(SupportedGamePackages.DARKWINTER),
                icon = R.drawable.ic_group,
                onClick = { chooseClientServerRegion(SupportedGamePackages.DARKWINTER) },
            ), matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.backup)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(2))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.settings_backup_detail)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(3))
            }, matchWidth())
            addView(ModernUi.listRow(
                context,
                getString(R.string.restore_full_backup),
                getString(R.string.restore_full_backup_description),
                R.drawable.ic_arrow_back,
                ::confirmFullRestore,
            ), matchWidth())
            addView(ModernUi.listRow(
                context,
                getString(R.string.back_up_all_information),
                getString(R.string.back_up_all_information_description),
                R.drawable.ic_save,
                ::exportFullBackup,
            ), matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.discord_webhook)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                val discordIcon = context.getDrawable(R.drawable.ic_discord)?.mutate()
                discordIcon?.setTint(currentTextColor)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    discordIcon, null, null, null,
                )
                compoundDrawablePadding = dp(8)
                setPadding(0, spacing, 0, dp(4))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.discord_webhook_description)
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            }, matchWidth())
            val webhookStore = DiscordWebhookSecretStore(context)
            val webhookConfigured = webhookStore.read() != null
            addView(TextView(context).apply {
                text = getString(
                    if (!webhookConfigured) {
                        R.string.discord_webhook_not_configured
                    } else {
                        R.string.discord_webhook_configured
                    },
                )
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                val backgroundColor = if (webhookConfigured) {
                    R.color.webhook_configured_background
                } else {
                    R.color.webhook_missing_background
                }
                val foregroundColor = if (webhookConfigured) {
                    R.color.webhook_configured_foreground
                } else {
                    R.color.webhook_missing_foreground
                }
                setTextColor(context.getColor(foregroundColor))
                background = GradientDrawable().apply {
                    setColor(context.getColor(backgroundColor))
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
            val webhookInput = EditText(context).apply {
                hint = getString(R.string.discord_webhook_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
            }
            addView(webhookInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) })
            addView(Button(context).apply {
                text = getString(R.string.save_discord_webhook)
                usePrimaryActionStyle()
                setOnClickListener {
                    val saved = runCatching {
                        webhookStore.save(webhookInput.text.toString())
                    }.isSuccess
                    TransientMessage.show(
                        this@OptionsActivity,
                        if (saved) {
                            R.string.discord_webhook_saved
                        } else {
                            R.string.discord_webhook_invalid
                        },
                        Toast.LENGTH_LONG,
                    )
                    if (saved) recreate()
                }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.clear_discord_webhook)
                useDestructiveTextActionStyle()
                isEnabled = webhookConfigured
                setOnClickListener {
                    val cleared = runCatching(webhookStore::clear).isSuccess
                    TransientMessage.show(
                        this@OptionsActivity,
                        if (cleared) R.string.discord_webhook_cleared else R.string.discord_webhook_clear_failed,
                        Toast.LENGTH_LONG,
                    )
                    if (cleared) recreate()
                }
            }, matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.payload_history)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(2))
            }, matchWidth())

            val capturePreferences = CapturePreferences(context)
            addView(packetHistoryOptionRow(
                title = getString(R.string.detailed_notifications),
                detail = getString(R.string.notification_required_explanation),
                checked = capturePreferences.detailedNotifications,
                enabled = true,
                onChanged = { capturePreferences.detailedNotifications = it },
            ), matchWidth())

            PayloadCatalog.categories.forEach { category ->
                addView(packetHistoryOptionRow(
                    title = getString(
                        R.string.payload_option_label,
                        payloadName(category.payloadType),
                        category.payloadType,
                    ),
                    detail = if (category.isRequired) {
                        getString(
                            R.string.required_payload_description,
                            payloadDescription(category.payloadType),
                        )
                    } else {
                        payloadDescription(category.payloadType)
                    },
                    checked = preferences.isEnabled(category.payloadType),
                    enabled = !category.isRequired,
                    onChanged = { preferences.setEnabled(category.payloadType, it) },
                ), matchWidth())
            }

            addView(TextView(context).apply {
                text = getString(R.string.last_capture_diagnostics)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(4))
            }, matchWidth())
            addView(TextView(context).apply {
                val diagnostics = CaptureDiagnosticsStore(context).read()
                text = if (diagnostics == null) {
                    getString(R.string.no_capture_diagnostics)
                } else {
                    getString(
                        R.string.capture_diagnostics,
                        diagnostics.stoppedAt.atZone(ZoneId.systemDefault()).format(DIAGNOSTIC_TIME),
                        diagnostics.forwardedBytes / 1024,
                        diagnostics.inspectedBytes / 1024,
                        diagnostics.decodedPayloads,
                        diagnostics.warnings,
                        diagnostics.droppedChunks,
                        diagnostics.unknownPayloads.ifBlank { getString(R.string.none) },
                    )
                }
                setTextIsSelectable(true)
            }, matchWidth())
        }
        return ScrollView(this).apply { addView(container, matchWidth()) }
    }

    /** Keeps a packet-history choice and its explanation in one compact visual group. */
    private fun packetHistoryOptionRow(
        title: CharSequence,
        detail: CharSequence,
        checked: Boolean,
        enabled: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        minimumHeight = dp(52)
        setPadding(0, dp(2), 0, dp(2))
        val checkBox = CheckBox(context).apply {
            contentDescription = title
            isChecked = checked
            isEnabled = enabled
            if (!enabled) {
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(getColor(R.color.primary), getColor(R.color.primary)),
                )
            }
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        addView(checkBox, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(getColor(if (enabled) R.color.text_primary else R.color.text_secondary))
            }, matchWidth())
            addView(TextView(context).apply {
                text = detail
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(2), 0, 0)
            }, matchWidth())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(4)
        })
        if (enabled) {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { checkBox.performClick() }
        }
    }

    private fun confirmFullRestore() {
        if (CaptureStatus.isRunning) {
            showBackupMessage(R.string.stop_capture_before_backup)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.restore_full_backup)
            .setMessage(R.string.restore_full_backup_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_full_backup) { _, _ -> openFullBackup() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun openFullBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, BACKUP_MIME_TYPES)
        startActivityForResult(intent, REQUEST_FULL_BACKUP_RESTORE)
    }

    @Suppress("DEPRECATION")
    private fun exportFullBackup() {
        if (requireActiveScope() == null) return
        if (CaptureStatus.isRunning) {
            showBackupMessage(R.string.stop_capture_before_backup)
            return
        }
        val title = "mobileGF2logger-full-${BACKUP_TIME.format(LocalDateTime.now())}." +
            PlatoonBackupManager.FILE_EXTENSION
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PlatoonBackupManager.MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, title)
        startActivityForResult(intent, REQUEST_FULL_BACKUP_EXPORT)
    }

    private fun hasBackupExtension(uri: Uri): Boolean {
        val name = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor: Cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        } ?: return false
        return BackupFileName.isValid(name)
    }

    private fun runBackupOperation(
        successMessage: Int,
        failureMessage: (Throwable) -> Int,
        operation: () -> Unit,
    ) {
        fileIoExecutor.execute {
            val message = runCatching(operation).fold(
                onSuccess = { successMessage },
                onFailure = failureMessage,
            )
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    showBackupMessage(message)
                    if (message == R.string.full_backup_restored) recreate()
                }
            }
        }
    }

    private fun showBackupMessage(message: Int) {
        TransientMessage.show(this, message, android.widget.Toast.LENGTH_LONG)
    }

    private fun requireActiveScope(): dev.gf2log.app.management.PlatoonStorageScope? =
        profileBinding.scope.also { scope ->
            if (scope == null) showBackupMessage(R.string.no_platoon_detected_detail)
        }

    private fun changeLanguage(language: String) {
        if (LanguagePreferences.get(this) == language) return
        LanguagePreferences.set(this, language)
        recreate()
    }

    private fun changeTheme(mode: String) {
        if (ThemePreferences.get(this) == mode) return
        ThemePreferences.set(this, mode)
        recreate()
    }

    private fun chooseGameServerRegion() {
        val storageScope = PlatoonProfileRegistry(this).activeScope()
        val regions = GameServerRegion.entries.filterNot { it == GameServerRegion.MANUAL }
        val choices = listOf(getString(R.string.server_region_auto)) + regions.map(::regionLabel)
        val automatic = GameTimeZonePreferences.isAutomatic(this, storageScope.storageId)
        val current = GameTimeZonePreferences.region(this, storageScope.storageId)
        val checked = if (automatic) 0 else regions.indexOf(current).takeIf { it >= 0 }?.plus(1) ?: -1
        AlertDialog.Builder(this)
            .setTitle(R.string.server_region)
            .setSingleChoiceItems(
                choices.toTypedArray(),
                checked,
            ) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    if (!automatic) {
                        updateGameTimeZone(storageScope, null)
                    }
                } else {
                    val selected = regions[which - 1]
                    if (automatic || selected != current) updateGameTimeZone(storageScope, selected)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseClientServerRegion(packageName: String) {
        val preferences = ClientServerRegionPreferences(this)
        val regions = preferences.allowed(packageName)
        val current = preferences.configured(packageName)
        AlertDialog.Builder(this)
            .setTitle(
                if (packageName == SupportedGamePackages.HAOPLAY) {
                    R.string.haoplay_capture_region
                } else {
                    R.string.darkwinter_capture_region
                },
            )
            .setSingleChoiceItems(
                regions.map(::regionLabel).toTypedArray(),
                regions.indexOf(current),
            ) { dialog, which ->
                preferences.set(packageName, regions[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateGameTimeZone(
        storageScope: PlatoonStorageScope,
        region: GameServerRegion?,
    ) {
        fileIoExecutor.execute {
            val previousAutomatic = GameTimeZonePreferences.isAutomatic(this, storageScope.storageId)
            val previousRegion = GameTimeZonePreferences.region(this, storageScope.storageId)
            val result = runCatching {
                if (region == null) {
                    GameTimeZonePreferences.clearRegionOverride(this, storageScope.storageId)
                } else {
                    GameTimeZonePreferences.setRegion(this, region, storageScope.storageId)
                }
                val zoneId = GameTimeZonePreferences.get(this, storageScope.storageId)
                try {
                    PlatoonRepository(this, storageScope)
                        .rebuildWeeklyHistoryForTimeZoneChange(zoneId)
                } catch (error: Exception) {
                    runCatching {
                        if (previousAutomatic) {
                            GameTimeZonePreferences.clearRegionOverride(this, storageScope.storageId)
                        } else {
                            GameTimeZonePreferences.setRegion(
                                this,
                                previousRegion,
                                storageScope.storageId,
                            )
                        }
                    }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                TransientMessage.show(
                    this,
                    if (result.isSuccess) R.string.game_timezone_updated
                    else R.string.game_timezone_update_failed,
                    Toast.LENGTH_SHORT,
                )
                recreate()
            }
        }
    }

    private fun resetSummary(region: GameServerRegion, automatic: Boolean): String {
        if (region == GameServerRegion.MANUAL) {
            return getString(
                R.string.manual_reset_region_summary,
                GameTimeZonePreferences.get(this).id,
                GameTimeZonePreferences.deviceZone().id,
            )
        }
        val localReset = region.nextReset()
            .atZone(GameTimeZonePreferences.deviceZone())
            .format(RESET_LOCAL_TIME)
        val resolved = getString(
            R.string.server_reset_summary,
            regionLabel(region),
            localReset,
            GameTimeZonePreferences.deviceZone().id,
        )
        return if (automatic) getString(R.string.auto_server_reset_summary, resolved) else resolved
    }

    private fun regionLabel(region: GameServerRegion): String = getString(
        when (region) {
            GameServerRegion.MANUAL -> R.string.server_region_manual
            GameServerRegion.DARKWINTER_GLOBAL -> R.string.server_region_darkwinter_global
            GameServerRegion.DARKWINTER_CHINA -> R.string.server_region_darkwinter_china
            GameServerRegion.HAOPLAY_GLOBAL -> R.string.server_region_haoplay_global
            GameServerRegion.HAOPLAY_JAPAN -> R.string.server_region_haoplay_japan
            GameServerRegion.HAOPLAY_KOREA -> R.string.server_region_haoplay_korea
            GameServerRegion.HAOPLAY_ASIA -> R.string.server_region_haoplay_asia
        },
    )

    private fun clientRegionLabel(packageName: String): String =
        ClientServerRegionPreferences(this).configured(packageName)
            ?.let(::regionLabel)
            ?: getString(R.string.server_region_not_configured)

    private fun payloadName(payloadType: Int): String = getString(
        when (payloadType) {
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE -> R.string.payload_name_platoon_profile
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> R.string.payload_name_platoon_members
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY -> R.string.payload_name_platoon_activity
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES -> R.string.payload_name_platoon_updates
            Gfl2PayloadDecoder.TYPE_WEAPONS -> R.string.payload_name_weapons
            Gfl2PayloadDecoder.TYPE_ATTACHMENTS -> R.string.payload_name_attachments
            Gfl2PayloadDecoder.TYPE_COMMON_KEYS -> R.string.payload_name_common_keys
            Gfl2PayloadDecoder.TYPE_FORMATIONS -> R.string.payload_name_formations
            else -> R.string.unknown_payload_type
        },
    )

    private fun payloadDescription(payloadType: Int): String = getString(
        when (payloadType) {
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE ->
                R.string.payload_description_platoon_profile
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> R.string.payload_description_platoon_members
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY ->
                R.string.payload_description_platoon_activity
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES ->
                R.string.payload_description_platoon_updates
            Gfl2PayloadDecoder.TYPE_WEAPONS -> R.string.payload_description_weapons
            Gfl2PayloadDecoder.TYPE_ATTACHMENTS -> R.string.payload_description_attachments
            Gfl2PayloadDecoder.TYPE_COMMON_KEYS -> R.string.payload_description_common_keys
            Gfl2PayloadDecoder.TYPE_FORMATIONS -> R.string.payload_description_formations
            else -> R.string.unknown_payload_type
        },
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val DIAGNOSTIC_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
        private val RESET_LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val BACKUP_MIME_TYPES = arrayOf(
            PlatoonBackupManager.MIME_TYPE,
            "application/zip",
            "application/octet-stream",
        )
        private const val REQUEST_FULL_BACKUP_EXPORT = 301
        private const val REQUEST_FULL_BACKUP_RESTORE = 302
    }
}

// Function Name: fullBackupRestoreFailureMessage
// Description:
// - Keeps known backup validation failures distinct from operational restore failures.
// - Prevents storage, document-provider, and unexpected errors from blaming the selected file.
// Parameters:
// - error: Failure raised while opening, validating, or restoring a complete backup.
// Returns:
// - Returns the invalid-backup message for validation failures.
// - Returns the operational restore-failure message for every other failure.
internal fun fullBackupRestoreFailureMessage(error: Throwable): Int =
    if (error is InvalidBackupException) {
        R.string.invalid_full_backup
    } else {
        R.string.full_backup_restore_failed
    }
