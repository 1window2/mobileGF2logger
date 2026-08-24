package dev.gf2log.app.capture

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
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

/** Resolves an original VPN flow to a supported Android package without endpoint guessing. */
internal object CaptureFlowOwnerResolver {
    fun resolve(
        context: Context,
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ): String? {
        val visiblePackages = SupportedGamePackages.all.mapNotNull { packageName ->
            runCatching {
                packageName to context.packageManager.getApplicationInfo(packageName, 0).uid
            }.getOrNull()
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(InetAddress.getByName(localAddress), localPort)
            val remote = InetSocketAddress(InetAddress.getByName(remoteAddress), remotePort)
            val forwardUid = runCatching {
                connectivity.getConnectionOwnerUid(
                    protocol,
                    local,
                    remote,
                )
            }.getOrNull()?.takeIf { it >= 0 }
            val forwardPackage = visiblePackages.firstOrNull { it.second == forwardUid }?.first
            val reverseUid = if (forwardPackage == null) runCatching {
                // Some vendor network stacks report VPN tuples in the opposite direction.
                connectivity.getConnectionOwnerUid(protocol, remote, local)
            }.getOrNull()?.takeIf { it >= 0 } else null
            forwardPackage ?: visiblePackages.firstOrNull { it.second == reverseUid }?.first
        } else {
            null
        }
        val installed = visiblePackages.map { it.first }
        return CaptureFlowOwnerPolicy.choose(resolved, installed)
    }
}

/** Falls back only when exactly one supported client is installed, avoiding ambiguous attribution. */
internal object CaptureFlowOwnerPolicy {
    fun choose(resolvedPackage: String?, installedPackages: Collection<String>): String? {
        if (resolvedPackage in SupportedGamePackages.all) return resolvedPackage
        return installedPackages
            .filter { it in SupportedGamePackages.all }
            .distinct()
            .singleOrNull()
    }
}
