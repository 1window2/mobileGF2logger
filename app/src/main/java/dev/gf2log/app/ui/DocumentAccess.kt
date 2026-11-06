package dev.gf2log.app.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import dev.gf2log.app.R
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths

/**
 * Opens only Storage Access Framework documents from the platform and Google document providers.
 * A user-selected destination can never resolve into another app's private storage.
 */
internal object DocumentAccess {
    private val TRUSTED_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
        "com.google.android.apps.docs.storage",
        "com.google.android.apps.nbu.files.provider",
    )

    fun openOutput(resolver: ContentResolver, destination: Uri): OutputStream {
        requireTrusted(destination)
        val path = destination.path ?: throw SecurityException("Export URI has no path")
        if (Paths.get(path).normalize().startsWith("/data")) {
            throw SecurityException("Export URI targets private app storage")
        }
        // Truncate so an overwritten document cannot keep stale trailing bytes; providers that
        // reject "wt" create a fresh document anyway, so plain "w" is the safe fallback.
        val output = try {
            resolver.openOutputStream(destination, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(destination, "w")
        } catch (_: java.io.FileNotFoundException) {
            resolver.openOutputStream(destination, "w")
        }
        return output ?: error("Document provider did not open an output stream")
    }

    /**
     * Writes an export into a document the picker has already created. A write that fails after
     * the document opened deletes it (best effort), so a truncated file never sits beside real
     * exports looking like one. A refused or failed open leaves the picked document as it was:
     * that may be a 0-byte file the picker created, which is the lesser harm, because the picker
     * may also have handed over an existing file the user chose to overwrite.
     */
    fun writeExport(resolver: ContentResolver, destination: Uri, write: (OutputStream) -> Unit) =
        writeOrDiscard(
            open = { openOutput(resolver, destination) },
            discard = { discard(resolver, destination) },
            write = write,
        )

    /**
     * Deletes a created document that will not receive its export; failures are ignored. A
     * document from a provider [openOutput] refuses is never touched.
     */
    fun discard(resolver: ContentResolver, destination: Uri) =
        discardTrusted(destination.scheme, destination.authority) {
            DocumentsContract.deleteDocument(resolver, destination)
        }

    internal fun discardTrusted(scheme: String?, authority: String?, delete: () -> Unit) {
        if (runCatching { requireTrusted(scheme, authority) }.isFailure) return
        runCatching(delete)
    }

    internal fun writeOrDiscard(open: () -> OutputStream, discard: () -> Unit, write: (OutputStream) -> Unit) {
        // Nothing has been truncated or written until open succeeds, so a refused open keeps the
        // picked document; once open succeeded, its old content is already gone.
        val output = open()
        try {
            output.use(write)
        } catch (error: Throwable) {
            runCatching(discard).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** The failure message for an export: a refused provider names the locations that work. */
    @StringRes
    fun exportFailureMessage(error: Throwable?, @StringRes fallback: Int): Int =
        if (error is UntrustedDocumentProviderException) R.string.export_location_unsupported else fallback

    fun openInput(resolver: ContentResolver, source: Uri): InputStream {
        requireTrusted(source)
        return resolver.openInputStream(source)
            ?: error("Document provider did not open an input stream")
    }

    fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun requireTrusted(uri: Uri) = requireTrusted(uri.scheme, uri.authority)

    internal fun requireTrusted(scheme: String?, authority: String?) {
        if (scheme != ContentResolver.SCHEME_CONTENT || authority !in TRUSTED_AUTHORITIES) {
            throw UntrustedDocumentProviderException()
        }
    }
}

/** A document outside the platform and Google providers; callers say which locations work. */
internal class UntrustedDocumentProviderException : SecurityException("Untrusted document provider")
