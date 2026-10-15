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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.dp
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One-time, bilingual walkthrough of the app's five user-facing workflows. */
class OnboardingActivity : BaseActivity() {

    override fun supportsPendingPlatoonAdmission(): Boolean = false

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
        val root = buildContent()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(20), bars.top + dp(8), bars.right + dp(20), bars.bottom + dp(12))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        renderPage(animate = false)
        // System back steps through the pages before leaving onboarding.
        onBackPressedDispatcher.addCallback(this, backToPreviousPage)
    }

    private val backToPreviousPage = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = previousPage()
    }

    private fun previousPage() {
        if (pageIndex == 0) return
        pageIndex--
        renderPage(animate = true, direction = -1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE_INDEX, pageIndex)
        super.onSaveInstanceState(outState)
    }

    /** Builds the stable header, content host, and bottom navigation frame. */
    private fun buildContent(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.app_background))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                Controls.segmentedControl(
                    context = context,
                    options = listOf(
                        LanguagePreferences.DEFAULT_LANGUAGE to "English",
                        LanguagePreferences.KOREAN to "한국어",
                    ),
                    selectedValue = LanguagePreferences.get(context),
                ) { language ->
                    if (LanguagePreferences.get(this@OnboardingActivity) != language) {
                        LanguagePreferences.set(this@OnboardingActivity, language)
                        recreate()
                    }
                }.apply { contentDescription = getString(R.string.onboarding_language_toggle) },
                LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
            skipButton = button(getString(R.string.onboarding_skip), Controls.Role.TERTIARY) { finishOnboarding() }
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
            backButton = button(getString(R.string.onboarding_back), Controls.Role.SECONDARY) { previousPage() }
            addView(backButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            })
            nextButton = button("", Controls.Role.PRIMARY) {
                if (pageIndex == PAGES.lastIndex) {
                    finishOnboarding()
                } else {
                    pageIndex++
                    renderPage(animate = true, direction = 1)
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
        backToPreviousPage.isEnabled = pageIndex > 0
        nextButton.text = getString(
            if (pageIndex == PAGES.lastIndex) R.string.onboarding_finish else R.string.onboarding_next,
        )
        skipButton.visibility = if (pageIndex == PAGES.lastIndex) View.INVISIBLE else View.VISIBLE
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
                textSize = 26f
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
                    background = GradientDrawable().apply {
                        setColor(getColor(R.color.warning_surface))
                        cornerRadius = dp(12).toFloat()
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
        if (!intent.getBooleanExtra(EXTRA_REPLAY, false)) {
            OnboardingPreferences.complete(this)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
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
        /** Opens the guide from Settings without touching the first-launch completion flag. */
        const val EXTRA_REPLAY = "onboarding.replay"
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
