package dev.gf2log.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureNoticeStringsTest {
    @Test
    fun englishNoticesNeverPutACountBeforeAPluralNoun() {
        // Unit tests run from the module directory.
        val strings = File("src/main/res/values/strings_capture.xml").readText(Charsets.UTF_8)
        // "Saved 1 members" or "decoded 1 payloads": a count argument followed by a plural noun.
        val countedPlural = Regex("""%\d+\${'$'}s\s+(?:queued\s+)?(?:members|payloads|chunks|warnings|items)\b""")

        val offenders = countedPlural.findAll(strings).map { it.value }.toList()
        assertTrue("Reword without a counted plural: $offenders", offenders.isEmpty())
    }

    @Test
    fun vpnRefusalNamesNoButtonHomeDoesNotHave() {
        val english = stringValue("src/main/res/values/strings.xml", "capture_vpn_needed")
        val korean = stringValue("src/main/res/values-ko/strings.xml", "capture_vpn_needed")

        // Home's start buttons are "Continuous" and "One-time capture"; neither is "Start capture".
        assertFalse(english, english.contains("Start capture"))
        assertTrue(english, english.contains("capture"))
        // In Korean, "기록" is the standby and scheduled-recording term, not Home capture.
        assertFalse(korean, korean.contains("기록"))
        assertTrue(korean, korean.contains("캡처"))
    }

    private fun stringValue(path: String, name: String): String {
        val strings = File(path).readText(Charsets.UTF_8)
        val match = Regex("""<string name="$name">([^<]*)</string>""").find(strings)
        return requireNotNull(match) { "$name missing from $path" }.groupValues[1]
    }
}
