package dev.gf2log.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import dev.gf2log.app.management.WeeklyShareProjection
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Renders privacy-filtered weekly PNGs into a private share cache. Each render receives a fresh
 * UUID-backed name; stale FileProvider grants are revoked before their files are removed, and only
 * the validated cache name survives Activity recreation.
 */
internal object WeeklyPngExport {
    fun directory(cacheDirectory: File): File = File(cacheDirectory, DIRECTORY_NAME)

    /** Image labels in the app's active language. */
    fun labels(context: Context) = WeeklyShareProjection.Labels(
        standardTitle = context.getString(R.string.png_title, context.getString(R.string.off_week)),
        gunsmokeTitle = context.getString(R.string.png_title, context.getString(R.string.gunsmoke_week)),
        member = context.getString(R.string.member),
        total = context.getString(R.string.total),
        privateNote = context.getString(R.string.member_private_note),
        anonymousMember = { context.getString(R.string.png_anonymous_member, it) },
        merit = context.getString(R.string.merit_short),
        score = context.getString(R.string.point_short),
        attempts = context.getString(R.string.attempt_short),
        login = context.getString(R.string.login_short),
        patrol = context.getString(R.string.patrol_short),
    )

    fun exportName(periodStart: LocalDate): String = "GF2logger-week-${periodStart.format(DATE)}.png"

    fun render(context: Context, document: WeeklyShareProjection.Document, periodStart: LocalDate): File {
        val directory = directory(context.cacheDir).apply { mkdirs() }
        require(directory.isDirectory) { "Unable to create weekly share cache" }
        revokeAndDeleteOld(context, directory)
        val target = newRenderTarget(context.cacheDir, periodStart)
        val temporary = File.createTempFile(".weekly-", ".png", directory)
        val bitmap = WeeklyReportPngRenderer.render(context, document)
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode weekly PNG" }
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Unable to publish weekly PNG" }
        } finally {
            bitmap.recycle()
            temporary.delete()
        }
        return target
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val title = context.getString(R.string.share_weekly_table)
        val share = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newUri(context.contentResolver, title, uri)
        context.startActivity(Intent.createChooser(share, title))
    }

    internal fun newRenderTarget(cacheDirectory: File, periodStart: LocalDate): File {
        val root = directory(cacheDirectory).canonicalFile
        require(root.isDirectory) { "Weekly PNG cache is unavailable" }
        val candidate = File(root, "GF2logger-week-${periodStart.format(DATE)}-${UUID.randomUUID()}.png").canonicalFile
        check(candidate.parentFile == root && !candidate.exists()) {
            "Weekly PNG target must be a new private cache file"
        }
        return candidate
    }

    fun nameForState(cacheDirectory: File, pendingFile: File?): String? = runCatching {
        val candidate = pendingFile?.canonicalFile ?: return@runCatching null
        val root = directory(cacheDirectory).canonicalFile
        candidate.name.takeIf { candidate.isFile && candidate.parentFile == root && it.matches(FILE_NAME) }
    }.getOrNull()

    fun restore(cacheDirectory: File, savedName: String?): File? = runCatching {
        val name = savedName?.takeIf { it.matches(FILE_NAME) } ?: return@runCatching null
        val root = directory(cacheDirectory).canonicalFile
        File(root, name).canonicalFile.takeIf { it.isFile && it.parentFile == root }
    }.getOrNull()

    private fun revokeAndDeleteOld(context: Context, directory: File) {
        directory.listFiles().orEmpty().filter(File::isFile).forEach { stale ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", stale)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            stale.delete()
        }
    }

    private const val DIRECTORY_NAME = "shared-weekly"
    private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val FILE_NAME = Regex(
        "GF2logger-week-\\d{8}-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.png",
    )
}
