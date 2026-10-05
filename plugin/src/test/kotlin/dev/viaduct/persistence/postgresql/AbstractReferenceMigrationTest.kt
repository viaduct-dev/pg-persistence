package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveAbstractReference
import dev.viaduct.persistence.jdbc.JdbcOperations
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Applies generated target checks to existing rows in a rollback-only PostgreSQL transaction. */
class AbstractReferenceMigrationTest {
    @Test
    fun `adding a possible type keeps existing references and permits the new target`() =
        withTable { db, table ->
            db.execute(checkSql(table, "person_id"))
            db.execute("INSERT INTO \"$table\" (person_id) VALUES (gen_random_uuid())")
            db.execute("ALTER TABLE \"$table\" ADD COLUMN group_id uuid")
            db.execute(checkSql(table, "person_id", "group_id"))
            db.execute("INSERT INTO \"$table\" (group_id) VALUES (gen_random_uuid())")
            assertEquals(2, db.rowCount(table))
        }

    @Test
    fun `expanded target check still rejects two populated targets`() =
        withTable { db, table ->
            db.execute(checkSql(table, "person_id"))
            db.execute("ALTER TABLE \"$table\" ADD COLUMN group_id uuid")
            db.execute(checkSql(table, "person_id", "group_id"))
            val failure =
                assertFailsWith<SQLException> {
                    db.execute("INSERT INTO \"$table\" VALUES (gen_random_uuid(), gen_random_uuid())")
                }
            assertEquals("23514", failure.sqlState)
        }

    @Test
    fun `removing a populated target from a required reference fails without a backfill`() =
        withTable { db, table ->
            db.execute("ALTER TABLE \"$table\" ADD COLUMN group_id uuid")
            db.execute(checkSql(table, "person_id", "group_id"))
            db.execute("INSERT INTO \"$table\" (group_id) VALUES (gen_random_uuid())")
            val savepoint = db.setSavepoint()
            val failure = assertFailsWith<SQLException> { db.execute(checkSql(table, "person_id")) }
            db.rollback(savepoint)
            assertEquals("23514", failure.sqlState)
            assertEquals(1, db.rowCount(table))
        }

    @Test
    fun `removing a target after backfill preserves data and tightens the check`() =
        withTable { db, table ->
            db.execute("ALTER TABLE \"$table\" ADD COLUMN group_id uuid")
            db.execute(checkSql(table, "person_id", "group_id"))
            db.execute("INSERT INTO \"$table\" (group_id) VALUES (gen_random_uuid())")
            db.execute("UPDATE \"$table\" SET person_id = gen_random_uuid(), group_id = NULL")
            db.execute(checkSql(table, "person_id"))
            db.execute("ALTER TABLE \"$table\" DROP COLUMN group_id")
            assertEquals(1, db.rowCount(table))
            val failure = assertFailsWith<SQLException> { db.execute("INSERT INTO \"$table\" DEFAULT VALUES") }
            assertEquals("23514", failure.sqlState)
        }

    @Test
    fun `changed possible types replace the same constraint and preserve physical column names`() {
        assertEquals(
            """
            ALTER TABLE "pg_temp"."activity" DROP CONSTRAINT IF EXISTS "activity_subject_target_check";
            ALTER TABLE "pg_temp"."activity" ADD CONSTRAINT "activity_subject_target_check" CHECK (num_nonnulls("person_id", "group_id") = 1);
            """.trimIndent(),
            checkSql("activity", "person_id", "group_id"),
        )
    }

    private fun checkSql(
        table: String,
        vararg columns: String,
    ): String =
        AbstractReferenceMigrationRenderer.render(
            PostgresqlMigrationOperation.AddAbstractCheck(
                EffectiveAbstractReference(
                    schemaName = "pg_temp",
                    tableName = table,
                    fieldName = "subject",
                    nullable = false,
                    columns = columns.associateWith { it },
                ),
            ),
        )

    private fun withTable(test: (Connection, String) -> Unit) {
        val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
        val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
        val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
        val connection = runCatching { DriverManager.getConnection(url, user, password) }.getOrNull()
        assumeTrue(connection != null, "Local PostgreSQL is unavailable")
        requireNotNull(connection).use { db ->
            db.autoCommit = false
            try {
                val table = "abstract_migration_" + UUID.randomUUID().toString().replace("-", "")
                db.execute("CREATE TEMP TABLE \"$table\" (person_id uuid)")
                test(db, table)
            } finally {
                db.rollback()
            }
        }
    }

    private fun Connection.execute(sql: String) {
        JdbcOperations.execute(this, sql)
    }

    private fun Connection.rowCount(table: String): Int =
        JdbcOperations.query(this, "SELECT count(*) FROM \"$table\"", { rows ->
            check(rows.next())
            rows.getInt(1)
        })
}
