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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByName(localAddress), localPort),
                InetSocketAddress(InetAddress.getByName(remoteAddress), remotePort),
            )
        }.getOrNull() ?: return null
        if (uid < 0) return null
        return context.packageManager.getPackagesForUid(uid)
            .orEmpty()
            .firstOrNull { it in SupportedGamePackages.all }
    }
}
