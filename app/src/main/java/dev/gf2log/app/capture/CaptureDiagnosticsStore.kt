package dev.gf2log.app.capture

import android.content.Context
import java.time.Instant

class CaptureDiagnosticsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun save(diagnostics: Diagnostics) {
        preferences.edit()
            .putLong(KEY_STARTED_AT, diagnostics.startedAt?.toEpochMilli() ?: -1)
            .putLong(KEY_STOPPED_AT, diagnostics.stoppedAt.toEpochMilli())
            .putLong(KEY_FORWARDED_BYTES, diagnostics.forwardedBytes)
            .putLong(KEY_INSPECTED_BYTES, diagnostics.inspectedBytes)
            .putLong(KEY_DECODED_PAYLOADS, diagnostics.decodedPayloads)
            .putLong(KEY_WARNINGS, diagnostics.warnings)
            .putLong(KEY_DROPPED_CHUNKS, diagnostics.droppedChunks)
            .putString(KEY_FINAL_STATUS, diagnostics.finalStatus)
            .apply()
    }

    fun read(): Diagnostics? {
        val stoppedAt = preferences.getLong(KEY_STOPPED_AT, -1)
        if (stoppedAt < 0) return null
        val startedAt = preferences.getLong(KEY_STARTED_AT, -1)
        return Diagnostics(
            startedAt = startedAt.takeIf { it >= 0 }?.let(Instant::ofEpochMilli),
            stoppedAt = Instant.ofEpochMilli(stoppedAt),
            forwardedBytes = preferences.getLong(KEY_FORWARDED_BYTES, 0),
            inspectedBytes = preferences.getLong(KEY_INSPECTED_BYTES, 0),
            decodedPayloads = preferences.getLong(KEY_DECODED_PAYLOADS, 0),
            warnings = preferences.getLong(KEY_WARNINGS, 0),
            droppedChunks = preferences.getLong(KEY_DROPPED_CHUNKS, 0),
            finalStatus = preferences.getString(KEY_FINAL_STATUS, "").orEmpty(),
        )
    }

    data class Diagnostics(
        val startedAt: Instant?,
        val stoppedAt: Instant,
        val forwardedBytes: Long,
        val inspectedBytes: Long,
        val decodedPayloads: Long,
        val warnings: Long,
        val droppedChunks: Long,
        val finalStatus: String,
    )

    companion object {
        private const val PREFERENCES = "capture_diagnostics"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_STOPPED_AT = "stopped_at"
        private const val KEY_FORWARDED_BYTES = "forwarded_bytes"
        private const val KEY_INSPECTED_BYTES = "inspected_bytes"
        private const val KEY_DECODED_PAYLOADS = "decoded_payloads"
        private const val KEY_WARNINGS = "warnings"
        private const val KEY_DROPPED_CHUNKS = "dropped_chunks"
        private const val KEY_FINAL_STATUS = "final_status"
    }
}
