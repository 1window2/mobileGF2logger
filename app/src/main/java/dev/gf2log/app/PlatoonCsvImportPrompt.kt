package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.ui.onTextChanged
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.LabeledField
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import java.util.concurrent.Executors

/** Selects or declares the explicit profile that will own an identity-free roster CSV. */
internal object PlatoonCsvImportPrompt {
    fun show(activity: Activity, onSelected: (PlatoonProfile) -> Unit) {
        showSelector(activity, PlatoonProfileRegistry(activity).active()?.storageId, onSelected)
    }

    private fun showSelector(activity: Activity, preselected: String?, onSelected: (PlatoonProfile) -> Unit) {
        val profiles = PlatoonProfileRegistry(activity).list()
        var selected = profiles.firstOrNull { it.storageId == preselected }
        lateinit var dialog: AlertDialog
        val choices = radioGroup(activity, profiles.map { "${it.platoonName}\n${Labels.profileIdentity(activity, it)}" }, profiles.indexOf(selected)) {
            selected = profiles[it]
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        }
        val content = Dialogs.content(activity) {
            addView(activity.text(activity.getString(R.string.csv_import_choose_destination), TextRole.SECONDARY), activity.lp())
            if (profiles.isEmpty()) {
                addView(activity.text(activity.getString(R.string.no_platoon_detected_detail), TextRole.BODY), activity.lp(top = 12))
            } else {
                addView(choices, activity.lp(top = 8))
            }
            // Start-aligned and content-sized so the plus icon sits beside its label like a choice row.
            addView(activity.button(activity.getString(R.string.add_new_platoon_profile), Controls.Role.TERTIARY, R.drawable.ic_add) {
                showCreate(activity) { created ->
                    dialog.dismiss()
                    showSelector(activity, created.storageId, onSelected)
                }
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, activity.lp(WRAP, WRAP, top = 8))
        }
        dialog = Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.csv_import_destination_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_action, null),
        ) { shown ->
            shown.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = selected != null
                setOnClickListener {
                    val target = selected ?: return@setOnClickListener
                    shown.dismiss()
                    onSelected(target)
                }
            }
        }
    }

    private fun showCreate(activity: Activity, onCreated: (PlatoonProfile) -> Unit) {
        val clients = listOf(PlatoonClient.HAOPLAY, PlatoonClient.DARKWINTER)
        var client = clients.first()
        var region: GameServerRegion? = null
        val name = LabeledField(
            activity,
            activity.getString(R.string.platoon_name),
            activity.textField(activity.getString(R.string.platoon_name), maxLength = PlatoonProfile.MAX_NAME_LENGTH),
        )
        val platoonId = LabeledField(
            activity,
            activity.getString(R.string.platoon_id),
            activity.textField(activity.getString(R.string.platoon_id_hint), inputType = InputType.TYPE_CLASS_NUMBER),
        )
        val servers = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val error = activity.text(null, TextRole.CAPTION) {
            setTextColor(activity.getColor(R.color.destructive_action))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        lateinit var dialog: AlertDialog
        fun validId(): Long? = platoonId.input.text.toString().toLongOrNull()?.takeIf { it in 1L..UInt.MAX_VALUE.toLong() }
        fun updateEnabled() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                name.input.text.isNotBlank() && validId() != null && region != null
        }
        fun rebuildServers() {
            region = null
            servers.removeAllViews()
            val allowed = ClientServerRegionPreferences.allowedFor(client.packageName)
            servers.addView(radioGroup(activity, allowed.map { Labels.region(activity, it) }, -1) {
                region = allowed[it]
                updateEnabled()
            })
        }
        val content = Dialogs.content(activity) {
            addView(activity.text(activity.getString(R.string.new_platoon_entry_message), TextRole.SECONDARY), activity.lp())
            addView(activity.text(activity.getString(R.string.platoon_client), TextRole.LABEL), activity.lp(top = 12))
            addView(Controls.segmentedControl(
                context = activity,
                options = clients.map { it.name to it.displayName },
                selectedValue = client.name,
            ) { value ->
                client = PlatoonClient.valueOf(value)
                rebuildServers()
                updateEnabled()
            }, activity.lp(top = 4))
            addView(activity.text(activity.getString(R.string.server_region), TextRole.LABEL), activity.lp(top = 12))
            addView(servers, activity.lp())
            addView(name, activity.lp(top = 12))
            addView(platoonId, activity.lp(top = 12))
            addView(error, activity.lp(top = 8))
        }
        rebuildServers()
        dialog = Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.enter_new_platoon_profile)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, null),
        ) { shown ->
            listOf(name.input, platoonId.input).forEach { field ->
                field.onTextChanged {
                    error.visibility = View.GONE
                    updateEnabled()
                }
            }
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                val selectedRegion = region ?: return@setOnClickListener
                val id = validId() ?: return@setOnClickListener
                val chosenClient = client
                val platoonName = name.input.text.toString()
                button.isEnabled = false
                profileExecutor.execute {
                    val result = runCatching {
                        PlatoonProfileRegistry(activity).createDeclared(chosenClient, selectedRegion, id, platoonName)
                    }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        button.isEnabled = true
                        result.fold(
                            onSuccess = {
                                shown.dismiss()
                                onCreated(it)
                            },
                            onFailure = {
                                error.text = activity.getString(R.string.unable_to_add_platoon_profile)
                                error.visibility = View.VISIBLE
                            },
                        )
                    }
                }
            }
            updateEnabled()
        }
    }

    private fun radioGroup(
        activity: Activity,
        labels: List<String>,
        checked: Int,
        onChecked: (Int) -> Unit,
    ): RadioGroup = RadioGroup(activity).apply {
        orientation = RadioGroup.VERTICAL
        val ids = labels.mapIndexed { index, label ->
            RadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_primary))
                buttonTintList = Controls.controlTint(activity)
                minimumHeight = activity.dp(48)
                setPadding(activity.dp(8), activity.dp(6), 0, activity.dp(6))
                isChecked = index == checked
            }.also { addView(it, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)) }.id
        }
        setOnCheckedChangeListener { _, checkedId ->
            ids.indexOf(checkedId).takeIf { it >= 0 }?.let(onChecked)
        }
    }

    private val profileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2ProfileCreate")
    }
}
