package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion

/** Selects or declares the explicit profile that will own an identity-free roster CSV. */
internal object PlatoonCsvImportPrompt {
    fun show(activity: Activity, onSelected: (PlatoonProfile) -> Unit) {
        showSelector(
            activity = activity,
            initiallySelectedStorageId = PlatoonProfileRegistry(activity).active()?.storageId,
            onSelected = onSelected,
        )
    }

    private fun showSelector(
        activity: Activity,
        initiallySelectedStorageId: String?,
        onSelected: (PlatoonProfile) -> Unit,
    ) {
        val registry = PlatoonProfileRegistry(activity)
        val profiles = registry.list()
        var selected = profiles.firstOrNull { it.storageId == initiallySelectedStorageId }
        val choices = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            profiles.forEach { profile ->
                addView(RadioButton(context).apply {
                    id = android.view.View.generateViewId()
                    text = profileLabel(activity, profile)
                    textSize = 14f
                    minimumHeight = activity.dp(48)
                    isChecked = profile.storageId == selected?.storageId
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected = profile
                    }
                }, matchWidth())
            }
        }
        val add = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_add_circle)
            contentDescription = activity.getString(R.string.add_new_platoon_profile)
            useModernIconStyle()
            imageTintList = null
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), 0)
            addView(choices, matchWidth())
            addView(
                add,
                LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = activity.dp(if (profiles.isEmpty()) 4 else 8)
                },
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.select_platoon)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.select, null)
            .create()
        dialog.setOnShowListener {
            val select = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = selected != null
                usePrimaryActionStyle()
                setOnClickListener {
                    val target = selected ?: return@setOnClickListener
                    dialog.dismiss()
                    onSelected(target)
                }
            }
            choices.setOnCheckedChangeListener { _, checkedId ->
                val index = (0 until choices.childCount)
                    .indexOfFirst { choices.getChildAt(it).id == checkedId }
                selected = profiles.getOrNull(index)
                select.isEnabled = selected != null
            }
            add.setOnClickListener {
                showCreate(activity, registry) { created ->
                    dialog.dismiss()
                    showSelector(activity, created.storageId, onSelected)
                }
            }
        }
        dialog.show()
    }

    private fun showCreate(
        activity: Activity,
        registry: PlatoonProfileRegistry,
        onCreated: (PlatoonProfile) -> Unit,
    ) {
        var client = PlatoonClient.HAOPLAY
        var region: GameServerRegion? = null
        val name = EditText(activity).apply {
            hint = activity.getString(R.string.platoon_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(PlatoonProfile.MAX_NAME_LENGTH))
            maxLines = 1
            setSingleLine(true)
        }
        val platoonIdInput = EditText(activity).apply {
            hint = activity.getString(R.string.platoon_id)
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setSingleLine(true)
        }
        val serverChoices = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
        }
        fun rebuildServerChoices() {
            region = null
            serverChoices.removeAllViews()
            ClientServerRegionPreferences.allowedFor(client.packageName).forEach { candidate ->
                serverChoices.addView(RadioButton(activity).apply {
                    id = android.view.View.generateViewId()
                    text = serverRegionLabel(activity, candidate)
                    textSize = 14f
                    minimumHeight = activity.dp(48)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) region = candidate
                    }
                }, matchWidth())
            }
        }
        rebuildServerChoices()
        val clients = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf(PlatoonClient.HAOPLAY, PlatoonClient.DARKWINTER).forEach { candidate ->
                addView(RadioButton(context).apply {
                    id = android.view.View.generateViewId()
                    text = candidate.displayName
                    textSize = 14f
                    minimumHeight = activity.dp(48)
                    isChecked = candidate == client
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            setOnCheckedChangeListener { group, checkedId ->
                val index = (0 until group.childCount)
                    .indexOfFirst { group.getChildAt(it).id == checkedId }
                client = listOf(PlatoonClient.HAOPLAY, PlatoonClient.DARKWINTER)
                    .getOrElse(index) { PlatoonClient.HAOPLAY }
                rebuildServerChoices()
            }
        }
        val error = TextView(activity).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.destructive_action))
            visibility = android.view.View.GONE
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(4), activity.dp(20), 0)
            addView(TextView(context).apply {
                text = activity.getString(R.string.new_platoon_entry_message)
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
            }, matchWidth())
            addView(fieldLabel(activity, R.string.platoon_client), matchWidth().apply {
                topMargin = activity.dp(16)
            })
            addView(clients, matchWidth())
            addView(fieldLabel(activity, R.string.server_region), matchWidth().apply {
                topMargin = activity.dp(8)
            })
            addView(serverChoices, matchWidth())
            addView(fieldLabel(activity, R.string.platoon_name), matchWidth().apply {
                topMargin = activity.dp(8)
            })
            addView(name, matchWidth())
            addView(fieldLabel(activity, R.string.platoon_id), matchWidth().apply {
                topMargin = activity.dp(8)
            })
            addView(platoonIdInput, matchWidth())
            addView(error, matchWidth().apply { topMargin = activity.dp(8) })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.enter_new_platoon_profile)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                usePrimaryActionStyle()
            }
            fun validId(): Long? = platoonIdInput.text.toString().toLongOrNull()
                ?.takeIf { it in 1L..UInt.MAX_VALUE.toLong() }
            fun updateEnabled() {
                confirm.isEnabled = name.text.toString().trim().isNotEmpty() &&
                    validId() != null &&
                    region != null
            }
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    error.visibility = android.view.View.GONE
                    updateEnabled()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            name.addTextChangedListener(watcher)
            platoonIdInput.addTextChangedListener(watcher)
            clients.setOnCheckedChangeListener { group, checkedId ->
                val index = (0 until group.childCount)
                    .indexOfFirst { group.getChildAt(it).id == checkedId }
                client = listOf(PlatoonClient.HAOPLAY, PlatoonClient.DARKWINTER)
                    .getOrElse(index) { PlatoonClient.HAOPLAY }
                rebuildServerChoices()
                serverChoices.setOnCheckedChangeListener { _, checkedRegionId ->
                    val regions = ClientServerRegionPreferences.allowedFor(client.packageName)
                    val selectedIndex = (0 until serverChoices.childCount)
                        .indexOfFirst { serverChoices.getChildAt(it).id == checkedRegionId }
                    region = regions.getOrNull(selectedIndex)
                    updateEnabled()
                }
                updateEnabled()
            }
            serverChoices.setOnCheckedChangeListener { _, checkedId ->
                val regions = ClientServerRegionPreferences.allowedFor(client.packageName)
                val index = (0 until serverChoices.childCount)
                    .indexOfFirst { serverChoices.getChildAt(it).id == checkedId }
                region = regions.getOrNull(index)
                updateEnabled()
            }
            confirm.setOnClickListener {
                val selectedRegion = region ?: return@setOnClickListener
                val platoonId = validId() ?: return@setOnClickListener
                runCatching {
                    registry.createDeclared(
                        client = client,
                        region = selectedRegion,
                        platoonId = platoonId,
                        platoonName = name.text.toString(),
                    )
                }.fold(
                    onSuccess = {
                        dialog.dismiss()
                        onCreated(it)
                    },
                    onFailure = {
                        error.text = activity.getString(R.string.unable_to_add_platoon_profile)
                        error.visibility = android.view.View.VISIBLE
                    },
                )
            }
            updateEnabled()
        }
        dialog.show()
    }

    private fun fieldLabel(activity: Activity, text: Int) = TextView(activity).apply {
        setText(text)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.text_secondary))
    }

    private fun profileLabel(activity: Activity, profile: PlatoonProfile): String =
        "${profile.client.displayName} / ${regionCode(profile.serverRegion)} / " +
            "${profile.platoonName} / ${profile.platoonId}"

    private fun regionCode(region: GameServerRegion): String = when (region) {
        GameServerRegion.MANUAL -> "Manual"
        GameServerRegion.DARKWINTER_GLOBAL, GameServerRegion.HAOPLAY_GLOBAL -> "GL"
        GameServerRegion.DARKWINTER_CHINA -> "CN"
        GameServerRegion.HAOPLAY_JAPAN -> "JP"
        GameServerRegion.HAOPLAY_KOREA -> "KR"
        GameServerRegion.HAOPLAY_ASIA -> "ASIA"
    }

    private fun serverRegionLabel(activity: Activity, region: GameServerRegion): String =
        activity.getString(
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

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun Activity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
