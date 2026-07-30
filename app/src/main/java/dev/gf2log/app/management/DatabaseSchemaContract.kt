package dev.gf2log.app.management

import android.database.sqlite.SQLiteDatabase

internal data class DatabaseSchemaContract(
    val tables: Map<String, TableContract>,
    val auxiliaryObjects: Set<SchemaObjectContract>,
) {
    data class TableContract(
        val columns: Map<String, ColumnContract>,
        val foreignKeys: Set<ForeignKeyContract>,
        val indexes: Map<String, IndexContract>,
        val autoIncrement: Boolean,
    )

    data class ColumnContract(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
        val hidden: Int,
    )

    data class ForeignKeyContract(
        val sequence: Int,
        val targetTable: String,
        val from: String,
        val to: String,
        val onUpdate: String,
        val onDelete: String,
        val match: String,
    )

    data class IndexContract(
        val unique: Boolean,
        val origin: String,
        val columns: List<IndexColumnContract>,
        val predicate: String?,
    )

    data class IndexColumnContract(
        val columnId: Int,
        val name: String?,
        val descending: Boolean,
        val collation: String,
        val key: Boolean,
    )

    data class SchemaObjectContract(
        val type: String,
        val name: String,
        val table: String,
        val sql: String,
    )

    companion object {
        fun read(database: SQLiteDatabase): DatabaseSchemaContract {
            val tableSql = database.rawQuery(
                "SELECT name, sql FROM sqlite_master " +
                    "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name != 'android_metadata' ORDER BY name",
                null,
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
            return DatabaseSchemaContract(
                tableSql.mapValues { (table, sql) ->
                    TableContract(
                        columns = readColumns(database, table),
                        foreignKeys = readForeignKeys(database, table),
                        indexes = readIndexes(database, table),
                        autoIncrement = AUTO_INCREMENT.containsMatchIn(sql.orEmpty()),
                    )
                },
                auxiliaryObjects = readAuxiliaryObjects(database),
            )
        }

        private fun readAuxiliaryObjects(database: SQLiteDatabase): Set<SchemaObjectContract> =
            database.rawQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE type IN ('trigger', 'view') ORDER BY type, name",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(
                            SchemaObjectContract(
                                type = cursor.getString(0).uppercase(),
                                name = cursor.getString(1),
                                table = cursor.getString(2),
                                sql = normalizeSql(cursor.getString(3)),
                            ),
                        )
                    }
                }
            }

        private fun readColumns(
            database: SQLiteDatabase,
            table: String,
        ): Map<String, ColumnContract> = database.rawQuery(
            "PRAGMA table_xinfo(${identifier(table)})",
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(1),
                        ColumnContract(
                            type = cursor.getString(2).uppercase(),
                            notNull = cursor.getInt(3) == 1,
                            defaultValue = cursor.getStringOrNull(4),
                            primaryKeyPosition = cursor.getInt(5),
                            hidden = cursor.getInt(6),
                        ),
                    )
                }
            }
        }

        private fun readForeignKeys(
            database: SQLiteDatabase,
            table: String,
        ): Set<ForeignKeyContract> = database.rawQuery(
            "PRAGMA foreign_key_list(${identifier(table)})",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyContract(
                            sequence = cursor.getInt(1),
                            targetTable = cursor.getString(2),
                            from = cursor.getString(3),
                            to = cursor.getString(4),
                            onUpdate = cursor.getString(5).uppercase(),
                            onDelete = cursor.getString(6).uppercase(),
                            match = cursor.getString(7).uppercase(),
                        ),
                    )
                }
            }
        }

        private fun readIndexes(
            database: SQLiteDatabase,
            table: String,
        ): Map<String, IndexContract> = database.rawQuery(
            "PRAGMA index_list(${identifier(table)})",
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    put(
                        name,
                        IndexContract(
                            unique = cursor.getInt(2) == 1,
                            origin = cursor.getString(3),
                            columns = readIndexColumns(database, name),
                            predicate = readIndexPredicate(database, name),
                        ),
                    )
                }
            }
        }

        private fun readIndexColumns(
            database: SQLiteDatabase,
            index: String,
        ): List<IndexColumnContract> = database.rawQuery(
            "PRAGMA index_xinfo(${identifier(index)})",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        IndexColumnContract(
                            columnId = cursor.getInt(1),
                            name = cursor.getStringOrNull(2),
                            descending = cursor.getInt(3) == 1,
                            collation = cursor.getString(4),
                            key = cursor.getInt(5) == 1,
                        ),
                    )
                }
            }
        }

        private fun readIndexPredicate(database: SQLiteDatabase, index: String): String? =
            database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(index),
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
                WHERE.find(cursor.getString(0))
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.replace(WHITESPACE, " ")
                    ?.uppercase()
            }

        private fun identifier(value: String): String =
            "\"${value.replace("\"", "\"\"")}\""

        private fun normalizeSql(value: String): String =
            value.trim().replace(WHITESPACE, " ").uppercase()

        private fun android.database.Cursor.getStringOrNull(column: Int): String? =
            if (isNull(column)) null else getString(column)

        private val AUTO_INCREMENT = Regex("\\bAUTOINCREMENT\\b", RegexOption.IGNORE_CASE)
        private val WHERE = Regex("\\bWHERE\\s+(.+)$", setOf(RegexOption.IGNORE_CASE))
        private val WHITESPACE = Regex("\\s+")
    }
}
