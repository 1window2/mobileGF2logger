package dev.gf2log.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.edit
import java.security.MessageDigest

/** How an installed supported client's signer compares with the one GF2logger pinned for it. */
internal enum class SupportedClientTrust {
    /** The package is not installed; any pin is kept for its next install. */
    NOT_INSTALLED,

    /** Installed, but GF2logger has not seen it while the user was using the app, so nothing is pinned. */
    UNPINNED,

    /** Signed by the pinned certificate, or by one that certificate rotated to. */
    VERIFIED,

    /** Installed under a signer other than the pinned one, until the user trusts it again. */
    MISMATCH,
}

/** What Android says about one package's signer; injected so [SupportedClientSigner] runs without Android. */
internal interface ClientSigningSource {
    /** Lowercase hex SHA-256 digests of [packageName]'s current signing certificates, or null when it is not installed. */
    fun currentDigests(packageName: String): Set<String>?

    /** True when [packageName] is signed by [digest], directly or through its key-rotation lineage. */
    fun hasSigner(packageName: String, digest: String): Boolean
}

/** Where pins are kept: per package, the digests trusted for it. */
internal interface SupportedClientPins {
    fun read(packageName: String): Set<String>?
    fun write(packageName: String, digests: Set<String>)
}

/**
 * Trust-on-first-use decisions for supported clients, with no Android dependencies. The publishers'
 * certificates are not known in advance, so the first signer seen while the user is using the app is
 * the one trusted; a later install under another signer is refused until the user trusts it again.
 */
internal object SupportedClientSignerPolicy {

    fun trust(pinned: Set<String>?, current: Set<String>?, hasSigner: (String) -> Boolean): SupportedClientTrust = when {
        current == null -> SupportedClientTrust.NOT_INSTALLED
        pinned.isNullOrEmpty() -> SupportedClientTrust.UNPINNED
        // Every pinned certificate must still sign it: a second signer cannot stand in for the first.
        current.isNotEmpty() && pinned.all(hasSigner) -> SupportedClientTrust.VERIFIED
        else -> SupportedClientTrust.MISMATCH
    }

    /** The digests to pin for a client seen for the first time, or null when it has a pin or no signer. */
    fun firstPin(pinned: Set<String>?, current: Set<String>?): Set<String>? =
        current?.takeIf { pinned.isNullOrEmpty() && it.isNotEmpty() && it.all(::isDigest) }

    /**
     * The digests a re-trust confirmation pins: only those the user was [shown], and only while the
     * client is still installed with exactly them, so a swap behind the dialog is not trusted.
     */
    fun retrustPin(shown: Set<String>, current: Set<String>?): Set<String>? =
        shown.takeIf { it.isNotEmpty() && it.all(::isDigest) && it == current }

    fun isDigest(value: String): Boolean =
        value.length == DIGEST_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** A short, readable fingerprint: the first [PREFIX_HEX] hex digits of each digest, in groups of four. */
    fun fingerprint(digests: Set<String>): String = digests.sorted().joinToString(", ") { digest ->
        digest.take(PREFIX_HEX).uppercase().chunked(4).joinToString(" ") + "…"
    }

    private const val DIGEST_HEX_LENGTH = 64
    private const val PREFIX_HEX = 16
}

/**
 * The pinned signers of [packages]. Pins are written only by [pinFirstSeen], which a screen calls
 * while the user is using the app, and by [retrust] after an explicit confirmation; capture,
 * standby, and scheduled recording only read them. Uninstalling a client keeps its pin, so the
 * same publisher's reinstall still matches and another signer's does not.
 */
internal class SupportedClientSigner(
    private val pins: SupportedClientPins,
    private val source: ClientSigningSource,
    private val packages: List<String> = SupportedGamePackages.all,
) {
    fun trust(packageName: String): SupportedClientTrust {
        require(packageName in packages)
        return SupportedClientSignerPolicy.trust(pinned(packageName), source.currentDigests(packageName)) { digest ->
            source.hasSigner(packageName, digest)
        }
    }

    fun isVerified(packageName: String): Boolean = trust(packageName) == SupportedClientTrust.VERIFIED

    /** The supported clients that may be routed, attributed, and launched. */
    fun verifiedPackages(): List<String> = packages.filter(::isVerified)

    fun pinned(packageName: String): Set<String>? =
        pins.read(packageName)?.filterTo(HashSet(), SupportedClientSignerPolicy::isDigest)?.takeIf { it.isNotEmpty() }

    fun current(packageName: String): Set<String>? = source.currentDigests(packageName)

    /** Pins each installed client that has no pin yet, returning those it pinned. */
    fun pinFirstSeen(): List<String> = synchronized(LOCK) {
        packages.filter { packageName ->
            val first = SupportedClientSignerPolicy.firstPin(pinned(packageName), current(packageName))
            first?.let { pins.write(packageName, it) } != null
        }
    }

    /** Trusts [shown], the signer the user confirmed for [packageName]; false when it changed meanwhile. */
    fun retrust(packageName: String, shown: Set<String>): Boolean = synchronized(LOCK) {
        require(packageName in packages)
        val pin = SupportedClientSignerPolicy.retrustPin(shown, current(packageName)) ?: return false
        pins.write(packageName, pin)
        true
    }

    private companion object {
        val LOCK = Any()
    }
}

/** The app's [SupportedClientSigner], reading Android's package signers and app-private pins. */
internal object SupportedClientSigners {
    /**
     * Pins live in their own private preferences file. Android backup excludes every preference file,
     * and the `.gf2backup` settings codec names its keys explicitly, so pins never leave the device.
     */
    private const val PREFS = "supported_client_signers"

    fun of(context: Context): SupportedClientSigner = SupportedClientSigner(
        PreferencePins(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        PackageManagerSigningSource(context.packageManager),
    )

    private class PreferencePins(private val prefs: SharedPreferences) : SupportedClientPins {
        override fun read(packageName: String): Set<String>? = prefs.getStringSet(key(packageName), null)?.toSet()

        override fun write(packageName: String, digests: Set<String>) {
            prefs.edit(commit = true) { putStringSet(key(packageName), HashSet(digests)) }
        }

        private fun key(packageName: String) = "signer.$packageName"
    }
}

/**
 * Android 9 and newer answer [hasSigner] with `hasSigningCertificate`, which also accepts a
 * certificate the client rotated away from under APK Signature Scheme v3. Android 8 has no rotation,
 * so the current signatures are compared directly.
 */
internal class PackageManagerSigningSource(private val packages: PackageManager) : ClientSigningSource {

    override fun currentDigests(packageName: String): Set<String>? {
        val signatures = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                legacySignatures(packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return signatures.orEmpty().mapTo(HashSet()) { sha256Hex(it.toByteArray()) }
    }

    override fun hasSigner(packageName: String, digest: String): Boolean {
        if (!SupportedClientSignerPolicy.isDigest(digest)) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packages.hasSigningCertificate(packageName, hexBytes(digest), PackageManager.CERT_INPUT_SHA256)
        } else {
            currentDigests(packageName)?.contains(digest) == true
        }
    }

    /** Android 8 only, where every signature of the APK is returned; the old multi-signer issue predates minSdk. */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun legacySignatures(packageName: String): Array<Signature>? =
        packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures

    private fun packageInfo(packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packages.getPackageInfo(packageName, flags)
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${HEX[value shr 4]}${HEX[value and 0xf]}"
        }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
