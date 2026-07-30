package dev.gf2log.app.management

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
