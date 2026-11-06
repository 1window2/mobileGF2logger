package dev.gf2log.app.ui

import dev.gf2log.app.R
import dev.gf2log.app.management.BackupArchive
import dev.gf2log.app.vpnConsentDeniedMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDocumentTest {
    @Test
    fun `a failed write deletes the created document once and reports the failure`() {
        var discards = 0
        var closed = false
        val stream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close() {
                closed = true
            }
        }
        val failure = IOException("disk full")

        val thrown = assertThrows(IOException::class.java) {
            DocumentAccess.writeOrDiscard(open = { stream }, discard = { discards += 1 }) { throw failure }
        }

        assertSame(failure, thrown)
        assertEquals(1, discards)
        assertTrue(closed)
    }

    @Test
    fun `a complete write keeps the document`() {
        var discards = 0
        val output = ByteArrayOutputStream()

        DocumentAccess.writeOrDiscard(open = { output }, discard = { discards += 1 }) { it.write(byteArrayOf(1, 2)) }

        assertEquals(0, discards)
        assertEquals(2, output.size())
    }

    @Test
    fun `an untrusted provider is refused before any stream opens and the document is discarded`() {
        var opened = false
        var discards = 0

        assertThrows(UntrustedDocumentProviderException::class.java) {
            DocumentAccess.writeOrDiscard(
                open = {
                    DocumentAccess.requireTrusted("content", "com.microsoft.skydrive.content.StorageAccessProvider")
                    opened = true
                    ByteArrayOutputStream()
                },
                discard = { discards += 1 },
            ) {}
        }

        assertTrue(!opened)
        assertEquals(1, discards)
        assertThrows(UntrustedDocumentProviderException::class.java) {
            DocumentAccess.requireTrusted("file", "com.android.externalstorage.documents")
        }
        DocumentAccess.requireTrusted("content", "com.android.externalstorage.documents")
    }

    @Test
    fun `only a refused provider replaces the export's own failure message`() {
        assertEquals(
            R.string.export_location_unsupported,
            DocumentAccess.exportFailureMessage(UntrustedDocumentProviderException(), R.string.status_export_failed),
        )
        assertEquals(
            R.string.status_export_failed,
            DocumentAccess.exportFailureMessage(SecurityException("Export URI has no path"), R.string.status_export_failed),
        )
        assertEquals(
            R.string.weekly_png_failed,
            DocumentAccess.exportFailureMessage(IOException(), R.string.weekly_png_failed),
        )
    }

    @Test
    fun `a backup refused before writing leaves an empty document, which is why it is deleted`() {
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.write(output, File("does-not-exist.db"), null)
        }

        assertEquals(0, output.size())
    }

    @Test
    fun `a refused VPN consent on Home is always explained`() {
        assertNull(vpnConsentDeniedMessage(android.app.Activity.RESULT_OK))
        assertEquals(R.string.capture_vpn_needed, vpnConsentDeniedMessage(android.app.Activity.RESULT_CANCELED))
        assertEquals(R.string.capture_vpn_needed, vpnConsentDeniedMessage(42))
    }
}
