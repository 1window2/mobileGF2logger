package dev.gf2log.app.capture

import android.net.VpnService

object NativeCaptureBridge {
    interface PayloadListener {
        fun onPayload(flowId: Long, isSent: Boolean, payload: ByteArray)

        fun onFlowClosed(flowId: Long)

        fun onTraffic(sentBytes: Long, receivedBytes: Long, inspectedBytes: Long)
    }

    val isAvailable: Boolean = try {
        System.loadLibrary("gf2capture")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun start(
        tunFileDescriptor: Int,
        vpnService: VpnService,
        listener: PayloadListener,
    ): Boolean {
        check(isAvailable) { "Native capture core is not bundled" }
        return nativeStart(tunFileDescriptor, vpnService, listener)
    }

    fun stop() {
        if (isAvailable) nativeStop()
    }

    private external fun nativeStart(
        tunFileDescriptor: Int,
        vpnService: VpnService,
        listener: PayloadListener,
    ): Boolean

    private external fun nativeStop()
}
