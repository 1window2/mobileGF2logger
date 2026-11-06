package dev.gf2log.app.standby

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

/**
 * Any app can read the next alarm clock's show intent, so it may only open a screen the manifest
 * already exports.
 */
class ScheduledRecordingShowIntentTest {

    @Test
    fun `the alarm clock show intent opens an exported screen`() {
        val target = ScheduledRecording.alarmShowScreen.name
        val activity = manifestActivities().firstOrNull { qualified(it.getAttribute("android:name")) == target }

        assertNotNull("$target is not declared in the manifest", activity)
        assertEquals("true", activity!!.getAttribute("android:exported"))
    }

    private fun manifestActivities(): List<Element> {
        // Unit tests run from the module directory.
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun qualified(name: String): String = if (name.startsWith(".")) "dev.gf2log.app$name" else name
}
