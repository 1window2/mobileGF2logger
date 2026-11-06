package dev.gf2log.app

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.settings.UserSettingsPreferences
import dev.gf2log.app.standby.StandbyAttendance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Home trusts first-seen clients only while someone is looking at it. */
@RunWith(AndroidJUnit4::class)
class HomeClientPinIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pins = context.getSharedPreferences("supported_client_signers", Context.MODE_PRIVATE)
    private lateinit var savedPins: Map<String, *>
    private var onboardingWasCompleted = false

    @Before
    fun setUp() {
        savedPins = pins.all.toMap()
        onboardingWasCompleted = OnboardingPreferences.isCompleted(context)
        OnboardingPreferences.complete(context)
        pins.edit().clear().commit()
    }

    @After
    fun tearDown() {
        StandbyAttendance.attended()
        pins.edit().clear().apply {
            savedPins.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
        if (!onboardingWasCompleted) UserSettingsPreferences.setOnboardingCompleted(context, false)
    }

    @Test
    fun homeResumedByAnUnattendedStandbyEndPinsOnlyOnTheFirstTouch() {
        val signers = SupportedClientSigners.of(context)
        // Needs a supported client installed: a stand-in under the game's package name will do.
        assumeTrue(SupportedGamePackages.all.any { signers.current(it) != null })
        // Standby finished on its own and closed its screen; Home resumes underneath it.
        StandbyAttendance.automationEnded()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertTrue(pins.all.toString(), pins.all.isEmpty())
            assertEquals(
                SupportedGamePackages.all.filter { signers.current(it) != null },
                SupportedGamePackages.all.filter { signers.trust(it) == SupportedClientTrust.UNPINNED },
            )

            scenario.onActivity { it.onUserInteraction() }

            assertTrue(SupportedGamePackages.all.filter { signers.current(it) != null }.all(signers::isVerified))
        }
    }
}
