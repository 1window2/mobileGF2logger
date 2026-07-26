package dev.gf2log.app;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class TrustedExportDestination {
    private static final Set<String> TRUSTED_DOCUMENT_AUTHORITIES = new HashSet<>(
            Arrays.asList(
                    "com.android.externalstorage.documents",
                    "com.android.providers.downloads.documents",
                    "com.android.providers.media.documents",
                    "com.google.android.apps.docs.storage",
                    "com.google.android.apps.nbu.files.provider"
            )
    );

    private TrustedExportDestination() {
    }

    static OutputStream openOutputStream(ContentResolver resolver, Uri destination)
            throws FileNotFoundException {
        if (!ContentResolver.SCHEME_CONTENT.equals(destination.getScheme())) {
            throw new SecurityException("Unsupported export URI scheme");
        }

        String authority = destination.getAuthority();
        if (authority == null || !TRUSTED_DOCUMENT_AUTHORITIES.contains(authority)) {
            throw new SecurityException("Untrusted document provider");
        }

        String path = destination.getPath();
        if (path == null) {
            throw new SecurityException("Export URI has no path");
        }

        Path normalizedPath = FileSystems.getDefault().getPath(path).normalize();
        if (normalizedPath.startsWith("/data")) {
            throw new SecurityException("Export URI targets private app storage");
        }

        return resolver.openOutputStream(destination);
    }
}
