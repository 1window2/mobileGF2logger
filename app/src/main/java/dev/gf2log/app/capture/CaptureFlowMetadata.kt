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

/**
 * Resolves an original VPN flow to a supported Android package without endpoint guessing.
 * One instance serves one capture session, so package UIDs are looked up once, not per flow.
 */
internal class CaptureFlowOwnerResolver(private val context: Context) {
    private val packageByUid: Map<Int, String> by lazy {
        SupportedGamePackages.all.mapNotNull { packageName ->
            runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).uid to packageName
            }.getOrNull()
        }.toMap()
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
