package dev.gf2log.app.management

/** Merges report fallbacks without replacing custom names captured in revision context. */
internal object WeeklyMemberNameProjection {
    fun merge(
        reportNamesByUid: Map<Long, String>,
        capturedNamesByUid: Map<Long, String>,
    ): Map<Long, String> = reportNamesByUid + capturedNamesByUid
}
