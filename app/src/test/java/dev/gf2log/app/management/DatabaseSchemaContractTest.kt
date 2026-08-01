package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DatabaseSchemaContractTest {
    @Test
    fun `schema contract detects relational changes`() {
        val expected = contract()

        assertNotEquals(
            expected,
            expected.copy(
                tables = expected.tables + (
                    "members" to requireNotNull(expected.tables["members"]).copy(
                        columns = emptyMap(),
                    )
                ),
            ),
        )
        assertNotEquals(
            expected,
            expected.copy(
                auxiliaryObjects = setOf(
                    DatabaseSchemaContract.SchemaObjectContract(
                        type = "TRIGGER",
                        name = "unexpected_trigger",
                        table = "members",
                        sql = "CREATE TRIGGER UNEXPECTED_TRIGGER AFTER INSERT ON MEMBERS BEGIN " +
                            "SELECT 1; END",
                    ),
                ),
            ),
        )
        assertNotEquals(
            expected,
            expected.copy(
                tables = expected.tables + (
                    "members" to requireNotNull(expected.tables["members"]).copy(
                        foreignKeys = emptySet(),
                    )
                ),
            ),
        )
    }

    @Test
    fun `equivalent indexes ignore historical table column order`() {
        val id = column(primaryKeyPosition = 1)
        val observedAt = column()
        val index = DatabaseSchemaContract.IndexContract(
            unique = false,
            origin = "c",
            columns = listOf(
                DatabaseSchemaContract.IndexColumnContract(
                    name = "observed_at",
                    descending = true,
                    collation = "BINARY",
                    key = true,
                ),
                DatabaseSchemaContract.IndexColumnContract(
                    name = null,
                    descending = false,
                    collation = "BINARY",
                    key = false,
                ),
            ),
            predicate = null,
        )
        val fresh = tableContract(
            columns = linkedMapOf("id" to id, "observed_at" to observedAt),
            indexes = mapOf("member_events_time" to index),
        )
        val migrated = tableContract(
            columns = linkedMapOf("observed_at" to observedAt, "id" to id),
            indexes = mapOf("member_events_time" to index),
        )

        assertEquals(fresh, migrated)
        assertNotEquals(
            fresh,
            migrated.copy(
                indexes = mapOf(
                    "member_events_time" to index.copy(
                        columns = index.columns.mapIndexed { position, indexedColumn ->
                            if (position == 0) indexedColumn.copy(descending = false)
                            else indexedColumn
                        },
                    ),
                ),
            ),
        )
    }

    private fun column(primaryKeyPosition: Int = 0) =
        DatabaseSchemaContract.ColumnContract(
            type = "INTEGER",
            notNull = false,
            defaultValue = null,
            primaryKeyPosition = primaryKeyPosition,
            hidden = 0,
        )

    private fun tableContract(
        columns: Map<String, DatabaseSchemaContract.ColumnContract>,
        indexes: Map<String, DatabaseSchemaContract.IndexContract>,
    ) = DatabaseSchemaContract.TableContract(
        columns = columns,
        foreignKeys = emptySet(),
        indexes = indexes,
        autoIncrement = false,
    )

    private fun contract() = DatabaseSchemaContract(
        tables = mapOf(
            "members" to DatabaseSchemaContract.TableContract(
                columns = mapOf(
                    "uid" to DatabaseSchemaContract.ColumnContract(
                        type = "INTEGER",
                        notNull = false,
                        defaultValue = null,
                        primaryKeyPosition = 1,
                        hidden = 0,
                    ),
                ),
                foreignKeys = setOf(
                    DatabaseSchemaContract.ForeignKeyContract(
                        sequence = 0,
                        targetTable = "parent",
                        from = "uid",
                        to = "uid",
                        onUpdate = "NO ACTION",
                        onDelete = "CASCADE",
                        match = "NONE",
                    ),
                ),
                indexes = emptyMap(),
                autoIncrement = false,
            ),
        ),
        auxiliaryObjects = emptySet(),
    )
}
