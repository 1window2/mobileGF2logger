package dev.gf2log.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One-time, bilingual walkthrough of the app's five user-facing workflows. */
class OnboardingActivity : LocalizedActivity() {
    private lateinit var pageHost: FrameLayout
    private lateinit var stepLabel: TextView
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button
    private var pageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageIndex = savedInstanceState?.getInt(STATE_PAGE_INDEX)
            ?.coerceIn(PAGES.indices)
            ?: 0
        setContentView(buildContent())
        renderPage(animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE_INDEX, pageIndex)
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                OnboardingLanguageToggle(context, LanguagePreferences.get(context)) { language ->
                    if (LanguagePreferences.get(this@OnboardingActivity) != language) {
                        LanguagePreferences.set(this@OnboardingActivity, language)
                        recreate()
                    }
                },
                LinearLayout.LayoutParams(dp(148), dp(40)),
            )
        }, matchWidth())

        addView(TextView(context).apply {
            text = getString(R.string.onboarding_welcome)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        }, matchWidth())
        addView(TextView(context).apply {
            text = getString(R.string.onboarding_intro)
            textSize = 15f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(16))
        }, matchWidth())

        pageHost = FrameLayout(context)
        addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        stepLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(12), 0, dp(8))
        }
        addView(stepLabel, matchWidth())

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            backButton = Button(context).apply {
                text = getString(R.string.onboarding_back)
                setOnClickListener {
                    if (pageIndex > 0) {
                        pageIndex--
                        renderPage(animate = true, direction = -1)
                    }
                }
            }
            addView(backButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            nextButton = Button(context).apply {
                usePrimaryActionStyle()
                setOnClickListener {
                    if (pageIndex == PAGES.lastIndex) {
                        finishOnboarding()
                    } else {
                        pageIndex++
                        renderPage(animate = true, direction = 1)
                    }
                }
            }
            addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            })
        }, matchWidth())

        skipButton = Button(context).apply {
            text = getString(R.string.onboarding_skip)
            contentDescription = getString(R.string.onboarding_skip)
            setOnClickListener { finishOnboarding() }
        }
        addView(skipButton, matchWidth())
    }

    private fun renderPage(animate: Boolean, direction: Int = 1) {
        val content = pageView(PAGES[pageIndex])
        pageHost.removeAllViews()
        pageHost.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        if (animate) {
            content.alpha = 0f
            content.translationX = (direction * dp(24)).toFloat()
            content.animate().alpha(1f).translationX(0f).setDuration(180L).start()
        }
        stepLabel.text = getString(R.string.onboarding_step, pageIndex + 1, PAGES.size)
        backButton.isEnabled = pageIndex > 0
        nextButton.text = getString(
            if (pageIndex == PAGES.lastIndex) R.string.onboarding_finish else R.string.onboarding_next,
        )
        skipButton.visibility = if (pageIndex == PAGES.lastIndex) View.INVISIBLE else View.VISIBLE
    }

    private fun pageView(page: OnboardingPage): View = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = ModernUi.panelBackground(context)
            elevation = 0f

            addView(FrameLayout(context).apply {
                background = ModernUi.panelBackground(context, emphasized = true)
                addView(ImageView(context).apply {
                    setImageResource(page.icon)
                    imageTintList = if (page.icon == R.mipmap.ic_launcher) null else
                        android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                }, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                bottomMargin = dp(16)
            })
            addView(TextView(context).apply {
                text = getString(page.title)
                textSize = 23f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(page.description)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(18))
            }, matchWidth())
            page.features.forEach { feature -> addView(featureRow(feature), matchWidth()) }
            page.warning?.let { warning ->
                addView(TextView(context).apply {
                    text = getString(warning)
                    textSize = 13f
                    setTextColor(getColor(R.color.warning_text))
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = ModernUi.panelBackground(context, emphasized = true).apply {
                        setColor(getColor(R.color.warning_surface))
                        setStroke(dp(1), getColor(R.color.warning_text))
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) })
            }
        }, matchWidth())
    }

    private fun featureRow(@StringRes feature: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, dp(7), 0, dp(7))
        addView(TextView(context).apply {
            text = "✓"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = getString(feature)
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun finishOnboarding() {
        OnboardingPreferences.complete(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private data class OnboardingPage(
        @StringRes val title: Int,
        @StringRes val description: Int,
        val features: List<Int>,
        @DrawableRes val icon: Int,
        @StringRes val warning: Int? = null,
    )

    companion object {
        private const val STATE_PAGE_INDEX = "page_index"
        private val PAGES = listOf(
            OnboardingPage(
                R.string.onboarding_main_title,
                R.string.onboarding_main_description,
                listOf(
                    R.string.onboarding_main_feature_1,
                    R.string.onboarding_main_feature_2,
                    R.string.onboarding_main_feature_3,
                ),
                R.mipmap.ic_launcher,
                R.string.onboarding_main_warning,
            ),
            OnboardingPage(
                R.string.onboarding_settings_title,
                R.string.onboarding_settings_description,
                listOf(
                    R.string.onboarding_settings_feature_1,
                    R.string.onboarding_settings_feature_2,
                    R.string.onboarding_settings_feature_3,
                ),
                R.drawable.ic_settings,
            ),
            OnboardingPage(
                R.string.onboarding_management_title,
                R.string.onboarding_management_description,
                listOf(
                    R.string.onboarding_management_feature_1,
                    R.string.onboarding_management_feature_2,
                    R.string.onboarding_management_feature_3,
                ),
                R.drawable.ic_edit,
            ),
            OnboardingPage(
                R.string.onboarding_weekly_title,
                R.string.onboarding_weekly_description,
                listOf(
                    R.string.onboarding_weekly_feature_1,
                    R.string.onboarding_weekly_feature_2,
                    R.string.onboarding_weekly_feature_3,
                ),
                R.drawable.ic_share,
            ),
            OnboardingPage(
                R.string.onboarding_packet_title,
                R.string.onboarding_packet_description,
                listOf(
                    R.string.onboarding_packet_feature_1,
                    R.string.onboarding_packet_feature_2,
                    R.string.onboarding_packet_feature_3,
                ),
                R.drawable.ic_save,
            ),
        )
    }
}

/** Two-label segmented control whose selection thumb slides between English and Korean. */
private class OnboardingLanguageToggle(
    context: Context,
    initialLanguage: String,
    private val onLanguageChanged: (String) -> Unit,
) : FrameLayout(context) {
    private val thumb = View(context)
    private val english = label("English") { select(LanguagePreferences.DEFAULT_LANGUAGE, true) }
    private val korean = label("한국어") { select(LanguagePreferences.KOREAN, true) }
    private var selectedLanguage = initialLanguage

    init {
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = ModernUi.panelBackground(context)
        contentDescription = context.getString(R.string.onboarding_language_toggle)
        thumb.background = ModernUi.panelBackground(context, emphasized = true).apply {
            setColor(context.getColor(R.color.primary))
            setStroke(0, context.getColor(R.color.primary))
        }
        addView(thumb)
        addView(english)
        addView(korean)
        post { select(selectedLanguage, false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(148), widthMeasureSpec)
        val height = resolveSize(dp(40), heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidth = (width - paddingLeft - paddingRight) / 2
        val childHeight = height - paddingTop - paddingBottom
        val widthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        repeat(childCount) { index -> getChildAt(index).measure(widthSpec, heightSpec) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left - paddingLeft - paddingRight
        val height = bottom - top - paddingTop - paddingBottom
        val half = width / 2
        thumb.layout(paddingLeft, paddingTop, paddingLeft + half, paddingTop + height)
        english.layout(paddingLeft, paddingTop, paddingLeft + half, paddingTop + height)
        korean.layout(paddingLeft + half, paddingTop, paddingLeft + width, paddingTop + height)
        thumb.translationX = if (selectedLanguage == LanguagePreferences.KOREAN) half.toFloat() else 0f
    }

    private fun label(textValue: String, action: () -> Unit) = TextView(context).apply {
        text = textValue
        gravity = Gravity.CENTER
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { action() }
    }

    private fun select(language: String, animate: Boolean) {
        selectedLanguage = language
        val distance = ((width - paddingLeft - paddingRight) / 2).toFloat()
        val target = if (language == LanguagePreferences.KOREAN) distance else 0f
        if (animate) {
            thumb.animate().cancel()
            thumb.animate()
                .translationX(target)
                .setDuration(180L)
                .withEndAction { onLanguageChanged(language) }
                .start()
        } else {
            thumb.translationX = target
        }
        val selectedColor = context.getColor(R.color.primary_action_foreground)
        val idleColor = context.getColor(R.color.text_primary)
        english.setTextColor(if (language == LanguagePreferences.DEFAULT_LANGUAGE) selectedColor else idleColor)
        korean.setTextColor(if (language == LanguagePreferences.KOREAN) selectedColor else idleColor)
    }

    private fun dp(value: Int): Int = context.dp(value)
}
