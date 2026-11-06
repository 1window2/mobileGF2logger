package dev.gf2log.app

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonNameConfirmationTest {
    @Test
    fun keyboardSpacingDoesNotBlockTheConfirmation() {
        listOf("MyPlatoon ", " MyPlatoon", "MyPlatoon\n").forEach { typed ->
            assertTrue(typed, PlatoonNameConfirmation.matches(typed, "MyPlatoon", 42))
        }
        assertTrue(PlatoonNameConfirmation.matches("My  Platoon", "My Platoon", 42))
    }

    @Test
    fun composedAndDecomposedSpellingsMatch() {
        val composed = "서클"
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)

        assertTrue(composed != decomposed)
        assertTrue(PlatoonNameConfirmation.matches(composed, decomposed, 42))
        assertTrue(PlatoonNameConfirmation.matches(decomposed, composed, 42))
    }

    @Test
    fun invisibleFormatAndBidiCharactersAreNotRequired() {
        assertTrue(PlatoonNameConfirmation.matches("AB", "A​B", 42))
        assertTrue(PlatoonNameConfirmation.matches("abc", "‮abc", 42))
        assertTrue(PlatoonNameConfirmation.matches("abc", "⁦abc⁩", 42))
        assertTrue(PlatoonNameConfirmation.matches("ab", "a\uD800b", 42))
        assertEquals("abc", PlatoonNameConfirmation.typeable("‮abc"))
        // A paired surrogate is a real character and is kept.
        assertEquals("a😀", PlatoonNameConfirmation.typeable("a😀"))
    }

    @Test
    fun aDifferentNameIsStillRejected() {
        assertFalse(PlatoonNameConfirmation.matches("MyPlatoon2", "MyPlatoon", 42))
        assertFalse(PlatoonNameConfirmation.matches("myplatoon", "MyPlatoon", 42))
        assertFalse(PlatoonNameConfirmation.matches("", "MyPlatoon", 42))
        assertFalse(PlatoonNameConfirmation.matches("42", "MyPlatoon", 42))
    }

    @Test
    fun aNameWithNothingTypeableIsConfirmedByItsId() {
        assertEquals("", PlatoonNameConfirmation.typeable("​‮"))
        assertFalse(PlatoonNameConfirmation.matches("", "​‮", 42))
        assertFalse(PlatoonNameConfirmation.matches("​‮", "​‮", 42))
        assertTrue(PlatoonNameConfirmation.matches(" 42 ", "​‮", 42))
    }
}
