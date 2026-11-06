package dev.gf2log.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WeeklyStringResourcesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** Canvas.drawText never breaks lines, so the PNG summary must be one line in every locale. */
    @Test
    fun `the PNG evidence summary is a single line in every locale`() {
        val values = everyLocale("png_evidence_summary")
        assertTrue(values.keys.containsAll(listOf("values", "values-ko")))

        values.forEach { (locale, value) ->
            assertFalse("$locale: $value", value.contains("\\n") || value.contains('\n'))
            // All five counts are still drawn.
            assertEquals(locale, 5, Regex("%[1-5]\\\$d").findAll(value).count())
        }
    }

    /** With no saved order the tables rank members, so resetting does not promise join-date order. */
    @Test
    fun `the member order reset warning does not promise join-date order`() {
        val english = everyLocale("reset_member_order_warning").getValue("values")
        val korean = everyLocale("reset_member_order_warning").getValue("values-ko")

        assertFalse(english, english.contains("join", ignoreCase = true))
        assertFalse(korean, korean.contains("가입일"))
    }

    private fun everyLocale(name: String): Map<String, String> =
        res.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
            .mapNotNull { directory -> File(directory, "strings.xml").takeIf(File::isFile) }
            .mapNotNull { file -> string(file, name)?.let { file.parentFile.name to it } }
            .toMap()

    private fun string(file: File, name: String): String? {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == name }
            ?.textContent
    }
}
