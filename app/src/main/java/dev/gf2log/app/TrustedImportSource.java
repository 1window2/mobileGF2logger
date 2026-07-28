package dev.gf2log.app;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class TrustedImportSource {
    private static final Set<String> TRUSTED_DOCUMENT_AUTHORITIES = new HashSet<>(
            Arrays.asList(
                    "com.android.externalstorage.documents",
                    "com.android.providers.downloads.documents",
                    "com.android.providers.media.documents",
                    "com.google.android.apps.docs.storage",
                    "com.google.android.apps.nbu.files.provider"
            )
    );

    private TrustedImportSource() {
    }

    static InputStream openInputStream(ContentResolver resolver, Uri source)
            throws FileNotFoundException {
        if (!ContentResolver.SCHEME_CONTENT.equals(source.getScheme())) {
            throw new SecurityException("Unsupported import URI scheme");
        }
        String authority = source.getAuthority();
        if (authority == null || !TRUSTED_DOCUMENT_AUTHORITIES.contains(authority)) {
            throw new SecurityException("Untrusted document provider");
        }
        return resolver.openInputStream(source);
    }
}
