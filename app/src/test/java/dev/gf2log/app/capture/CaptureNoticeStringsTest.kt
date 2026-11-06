package dev.gf2log.app.capture

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
}
