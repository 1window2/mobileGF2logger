package dev.gf2log.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
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

    /** Onboarding keeps the approved editorial dark surface in every device theme. */
    override fun preferredTheme(context: Context): String = ThemePreferences.DARK

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

    /** Builds the stable header, content host, and bottom navigation frame. */
    private fun buildContent(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(12))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                OnboardingLanguageToggle(context, LanguagePreferences.get(context)) { language ->
                    if (LanguagePreferences.get(this@OnboardingActivity) != language) {
                        LanguagePreferences.set(this@OnboardingActivity, language)
                        recreate()
                    }
                },
                LinearLayout.LayoutParams(dp(120), dp(54)),
            )
            addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
            skipButton = Button(context).apply {
                text = getString(R.string.onboarding_skip)
                contentDescription = getString(R.string.onboarding_skip)
                useTertiaryActionStyle()
                setOnClickListener { finishOnboarding() }
            }
            addView(skipButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48),
            ))
        }, matchWidth())

        pageHost = FrameLayout(context)
        addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            backButton = Button(context).apply {
                text = getString(R.string.onboarding_back)
                useSecondaryActionStyle()
                setOnClickListener {
                    if (pageIndex > 0) {
                        pageIndex--
                        renderPage(animate = true, direction = -1)
                    }
                }
            }
            addView(backButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
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
            addView(nextButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            })
        }, matchWidth())

        stepLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(5), 0, 0)
        }
        addView(stepLabel, matchWidth())
    }

    /** Replaces one page while keeping progress and action state synchronized. */
    private fun renderPage(animate: Boolean, direction: Int = 1) {
        val content = pageView(PAGES[pageIndex])
        pageHost.removeAllViews()
        pageHost.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            content.alpha = 0f
            content.translationX = (direction * dp(12)).toFloat()
            content.animate().alpha(1f).translationX(0f).setDuration(140L).start()
        }
        stepLabel.text = getString(R.string.onboarding_step, pageIndex + 1, PAGES.size)
        stepLabel.contentDescription = stepLabel.text
        backButton.isEnabled = pageIndex > 0
        nextButton.text = getString(
            if (pageIndex == PAGES.lastIndex) R.string.onboarding_finish else R.string.onboarding_next,
        )
        nextButton.isEnabled = true
        nextButton.usePrimaryActionStyle()
        skipButton.visibility = View.VISIBLE
    }

    /** Builds one scroll-safe page from localized product copy and authored icon roles. */
    private fun pageView(page: OnboardingPage): View = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))

            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.app_background))
                    setStroke(dp(1), getColor(R.color.outline_strong))
                }
                addView(ImageView(context).apply {
                    setImageResource(page.icon)
                    imageTintList = if (page.icon == R.mipmap.ic_launcher) null else
                        ColorStateList.valueOf(getColor(R.color.text_primary))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(16) })

            if (pageIndex == 0) {
                addView(TextView(context).apply {
                    text = getString(R.string.onboarding_welcome_prefix)
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(getColor(R.color.accent))
                    setTypeface(typeface, Typeface.BOLD)
                }, matchWidth())
            }
            addView(TextView(context).apply {
                text = if (pageIndex == 0) getString(R.string.app_name) else getString(page.title)
                gravity = Gravity.CENTER
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(page.description)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(12), dp(7), dp(12), dp(14))
            }, matchWidth())
            addView(progressView(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(24),
            ).apply { bottomMargin = dp(8) })
            page.features.forEachIndexed { index, feature ->
                addView(featureRow(feature, page.featureIcons[index]), matchWidth())
            }
            page.warning?.let { warning ->
                addView(TextView(context).apply {
                    text = getString(warning)
                    textSize = 13f
                    setTextColor(getColor(R.color.warning_text))
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = ModernUi.panelBackground(context, emphasized = true).apply {
                        setColor(getColor(R.color.warning_surface))
                        cornerRadius = dp(8).toFloat()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) })
            }
        }, matchWidth())
    }

    private fun progressView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        PAGES.indices.forEach { index ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(getColor(if (index == pageIndex) R.color.accent else R.color.outline_strong))
                }
            }, LinearLayout.LayoutParams(dp(if (index == pageIndex) 24 else 18), dp(4)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    private fun featureRow(@StringRes feature: Int, @DrawableRes icon: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(TextView(context).apply {
                text = getString(feature)
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setPadding(dp(12), 0, 0, 0)
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
        val featureIcons: List<Int>,
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
                listOf(R.drawable.ic_home, R.drawable.ic_group, R.drawable.ic_calendar),
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
                listOf(R.drawable.ic_settings, R.drawable.ic_save, R.drawable.ic_discord),
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
                listOf(R.drawable.ic_group, R.drawable.ic_edit, R.drawable.ic_save),
                R.drawable.ic_group,
            ),
            OnboardingPage(
                R.string.onboarding_weekly_title,
                R.string.onboarding_weekly_description,
                listOf(
                    R.string.onboarding_weekly_feature_1,
                    R.string.onboarding_weekly_feature_2,
                    R.string.onboarding_weekly_feature_3,
                ),
                listOf(R.drawable.ic_calendar, R.drawable.ic_edit, R.drawable.ic_share),
                R.drawable.ic_calendar,
            ),
            OnboardingPage(
                R.string.onboarding_packet_title,
                R.string.onboarding_packet_description,
                listOf(
                    R.string.onboarding_packet_feature_1,
                    R.string.onboarding_packet_feature_2,
                    R.string.onboarding_packet_feature_3,
                ),
                listOf(R.drawable.ic_save, R.drawable.ic_discord, R.drawable.ic_calendar),
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
    private var selectedLanguage = initialLanguage
    private val english = label("English", LanguagePreferences.DEFAULT_LANGUAGE)
    private val korean = label("한국어", LanguagePreferences.KOREAN)

    init {
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = ModernUi.panelBackground(context).apply { cornerRadius = dp(12).toFloat() }
        thumb.background = ModernUi.panelBackground(context, emphasized = true).apply {
            cornerRadius = dp(9).toFloat()
        }
        addView(thumb)
        addView(english)
        addView(korean)
        post { select(selectedLanguage, false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(120), widthMeasureSpec)
        val height = resolveSize(dp(54), heightMeasureSpec)
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

    private fun label(textValue: String, language: String) = RadioButton(context).apply {
        text = textValue
        buttonDrawable = null
        gravity = Gravity.CENTER
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        minimumHeight = dp(48)
        isChecked = selectedLanguage == language
        setOnClickListener { select(language, true) }
    }

    private fun select(language: String, animate: Boolean) {
        selectedLanguage = language
        val distance = ((width - paddingLeft - paddingRight) / 2).toFloat()
        val target = if (language == LanguagePreferences.KOREAN) distance else 0f
        english.isChecked = language == LanguagePreferences.DEFAULT_LANGUAGE
        korean.isChecked = language == LanguagePreferences.KOREAN
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            thumb.animate().cancel()
            thumb.animate()
                .translationX(target)
                .setDuration(140L)
                .withEndAction { onLanguageChanged(language) }
                .start()
        } else {
            thumb.translationX = target
            if (animate) onLanguageChanged(language)
        }
        val selectedColor = context.getColor(R.color.accent_text)
        val idleColor = context.getColor(R.color.text_primary)
        english.setTextColor(if (language == LanguagePreferences.DEFAULT_LANGUAGE) selectedColor else idleColor)
        korean.setTextColor(if (language == LanguagePreferences.KOREAN) selectedColor else idleColor)
    }

    private fun dp(value: Int): Int = context.dp(value)
}
