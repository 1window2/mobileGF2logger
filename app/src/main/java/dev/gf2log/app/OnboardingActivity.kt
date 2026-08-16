package dev.gf2log.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
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
        setPadding(dp(20), dp(10), dp(20), dp(12))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                OnboardingLanguageToggle(context, LanguagePreferences.get(context)) { language ->
                    if (LanguagePreferences.get(this@OnboardingActivity) != language) {
                        LanguagePreferences.set(this@OnboardingActivity, language)
                        recreate()
                    }
                },
                LinearLayout.LayoutParams(dp(136), dp(42)),
            )
        }, matchWidth())

        pageHost = FrameLayout(context)
        addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        stepLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            letterSpacing = 0.16f
            setTextColor(getColor(R.color.accent))
            setPadding(0, dp(8), 0, dp(6))
        }
        addView(stepLabel, matchWidth())

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
            useTertiaryActionStyle()
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
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            content.alpha = 0f
            content.translationX = (direction * dp(12)).toFloat()
            content.animate().alpha(1f).translationX(0f).setDuration(140L).start()
        }
        stepLabel.text = PAGES.indices.joinToString("  ") { index ->
            if (index == pageIndex) "●" else "○"
        }
        stepLabel.contentDescription = getString(R.string.onboarding_step, pageIndex + 1, PAGES.size)
        backButton.isEnabled = pageIndex > 0
        nextButton.text = getString(
            if (pageIndex == PAGES.lastIndex) R.string.onboarding_finish else R.string.onboarding_next,
        )
        // Reapply the role after locale recreation so the translated final action cannot
        // fall back to the platform button drawable while the page is restored.
        nextButton.isEnabled = true
        nextButton.usePrimaryActionStyle()
        skipButton.visibility = View.VISIBLE
    }

    private fun pageView(page: OnboardingPage): View = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(22), 0, dp(10))

            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.accent_surface))
                    setStroke(dp(1), getColor(R.color.outline))
                }
                addView(ImageView(context).apply {
                    setImageResource(page.icon)
                    imageTintList = if (page.icon == R.mipmap.ic_launcher) null else
                        android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(100), dp(100)).apply { bottomMargin = dp(20) })
            addView(TextView(context).apply {
                text = getString(page.title)
                gravity = Gravity.CENTER
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(page.description)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(12), dp(7), dp(12), dp(18))
            }, matchWidth())
            page.features.forEach { feature ->
                addView(featureRow(feature), matchWidth())
            }
            page.warning?.let { warning ->
                addView(TextView(context).apply {
                    text = getString(warning)
                    textSize = 14f
                    setTextColor(getColor(R.color.warning_text))
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    background = ModernUi.panelBackground(context, emphasized = true).apply {
                        setColor(getColor(R.color.warning_surface))
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) })
            }
        }, matchWidth())
    }

    private fun featureRow(@StringRes feature: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        addView(TextView(context).apply {
            text = "•"
            textSize = 20f
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(context).apply {
            text = getString(feature)
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(8), 0, 0, 0)
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
        thumb.background = ModernUi.panelBackground(context, emphasized = true)
        addView(thumb)
        addView(english)
        addView(korean)
        post { select(selectedLanguage, false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(136), widthMeasureSpec)
        val height = resolveSize(dp(42), heightMeasureSpec)
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
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            thumb.animate().cancel()
            thumb.animate()
                .translationX(target)
                .setDuration(140L)
                .withEndAction { onLanguageChanged(language) }
                .start()
        } else {
            thumb.translationX = target
        }
        val selectedColor = context.getColor(R.color.accent_text)
        val idleColor = context.getColor(R.color.text_primary)
        english.setTextColor(if (language == LanguagePreferences.DEFAULT_LANGUAGE) selectedColor else idleColor)
        korean.setTextColor(if (language == LanguagePreferences.KOREAN) selectedColor else idleColor)
    }

    private fun dp(value: Int): Int = context.dp(value)
}
