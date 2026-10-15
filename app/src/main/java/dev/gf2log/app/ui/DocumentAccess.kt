package dev.gf2log.app.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
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

    private fun requireTrusted(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw SecurityException("Unsupported document URI scheme")
        }
        if (uri.authority !in TRUSTED_AUTHORITIES) {
            throw SecurityException("Untrusted document provider")
        }
    }
}
