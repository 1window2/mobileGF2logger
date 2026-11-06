package dev.gf2log.app.management

import android.database.sqlite.SQLiteDatabaseCorruptException

/**
 * Why a Platoon's data could not be read or written, as far as the user or a background task can
 * act on it. Screens, capture and maintenance all classify a failure here, so each one names the
 * same cause for the same exception wherever it surfaces along the cause chain.
 */
internal enum class PlatoonAccessFailure {
    /**
     * The database was moved aside or failed its integrity check. Retrying cannot help; a backup
     * restore or an explicit rebuild from retained roster CSVs can.
     */
    DATABASE_DAMAGED,

    /** Another thread's CSV import, rollback or undo holds the Platoon; retrying shortly works. */
    IMPORT_IN_PROGRESS,

    /** Anything else; callers keep their own generic message. */
    OTHER,
    ;

    companion object {
        /**
         * Damage wins over a claim: a damaged Platoon stays unusable after the import ends. SQLite
         * corruption counts as damage too, because its handler has already moved the file aside.
         */
        fun of(error: Throwable?): PlatoonAccessFailure {
            val chain = generateSequence(error) { it.cause }.toList()
            return when {
                chain.any { it is PlatoonDatabaseDamagedException || it is SQLiteDatabaseCorruptException } ->
                    DATABASE_DAMAGED
                chain.any { it is CsvImportInProgressException } -> IMPORT_IN_PROGRESS
                else -> OTHER
            }
        }
    }
}
