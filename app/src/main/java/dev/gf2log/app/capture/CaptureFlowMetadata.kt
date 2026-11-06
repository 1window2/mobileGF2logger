package dev.gf2log.app.capture

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import dev.gf2log.app.SupportedClientSigners
import dev.gf2log.app.SupportedClientTrust
import dev.gf2log.app.SupportedGamePackages
import java.net.InetAddress
import java.net.InetSocketAddress

internal data class CaptureFlowMetadata(
    val protocol: Int,
    val localAddress: String,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val ownerPackage: String?,
)

/**
 * Resolves an original VPN flow to a supported Android package without endpoint guessing.
 * One instance serves one capture session, so package UIDs are looked up once, not per flow. Only
 * a client signed by its trusted signer has its UID mapped, so another app under its package name
 * is never attributed to it, nor counted by the single-client fallback.
 */
internal class CaptureFlowOwnerResolver(private val context: Context) {
    private val packageByUid: Map<Int, String> by lazy {
        val signers = SupportedClientSigners.of(context)
        CaptureFlowOwnerPolicy.trustedUids(
            SupportedGamePackages.all,
            uidOf = { packageName ->
                runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            },
            isVerified = signers::isVerified,
        )
    }

    fun resolve(
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ): String? {
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(InetAddress.getByName(localAddress), localPort)
            val remote = InetSocketAddress(InetAddress.getByName(remoteAddress), remotePort)
            fun owner(source: InetSocketAddress, destination: InetSocketAddress): String? =
                runCatching { connectivity.getConnectionOwnerUid(protocol, source, destination) }
                    .getOrNull()
                    ?.takeIf { it >= 0 }
                    ?.let(packageByUid::get)
            // Some vendor network stacks report VPN tuples in the opposite direction.
            owner(local, remote) ?: owner(remote, local)
        } else {
            null
        }
        return CaptureFlowOwnerPolicy.choose(resolved, packageByUid.values)
    }
}

/** Falls back only when exactly one verified supported client is installed, avoiding ambiguous attribution. */
internal object CaptureFlowOwnerPolicy {
    fun choose(resolvedPackage: String?, verifiedPackages: Collection<String>): String? {
        val trusted = verifiedPackages.filter { it in SupportedGamePackages.all }.distinct()
        if (resolvedPackage != null && resolvedPackage in trusted) return resolvedPackage
        return trusted.singleOrNull()
    }

    /** The UIDs of the installed [packages] whose signer is verified; an unverified one maps nothing. */
    fun trustedUids(
        packages: Collection<String>,
        uidOf: (String) -> Int?,
        isVerified: (String) -> Boolean,
    ): Map<Int, String> = packages
        .filter { it in SupportedGamePackages.all && isVerified(it) }
        .mapNotNull { packageName -> uidOf(packageName)?.let { it to packageName } }
        .toMap()
}

/** Why a capture with no client to route stops: a changed or untrusted signer is named before "not installed". */
internal object CaptureTargetPolicy {
    fun noTargetNotice(trust: Collection<SupportedClientTrust>): CaptureNotice = when {
        SupportedClientTrust.MISMATCH in trust -> CaptureNotice.CLIENT_SIGNER_CHANGED
        SupportedClientTrust.UNPINNED in trust -> CaptureNotice.CLIENT_NOT_TRUSTED
        else -> CaptureNotice.NO_SUPPORTED_CLIENT
    }
}
