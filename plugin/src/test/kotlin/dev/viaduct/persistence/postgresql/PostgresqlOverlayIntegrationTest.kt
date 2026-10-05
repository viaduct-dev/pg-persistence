package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateArray
import dev.viaduct.persistence.hibernate.EffectiveHibernateComputedRelationship
import dev.viaduct.persistence.hibernate.EffectiveHibernateEdgeField
import dev.viaduct.persistence.hibernate.EffectiveHibernateJoinTable
import dev.viaduct.persistence.hibernate.EffectiveHibernateModel
import dev.viaduct.persistence.hibernate.EffectiveHibernateRelationship
import dev.viaduct.persistence.hibernate.EffectiveHibernateTable
import dev.viaduct.persistence.hibernate.GraphqlNameKind
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Executes generated migration and pg_graphql metadata SQL against a real PostgreSQL catalog. */
class PostgresqlOverlayIntegrationTest {
    @Test
    fun `creates an idempotent foreign key with its pg_graphql name`() {
        connectionOrSkip().use { connection ->
            val schema = "viaduct_test_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                connection.execute("CREATE SCHEMA ${quoteIdentifier(schema)}")
                connection.execute(
                    """
                    CREATE TABLE ${qualifiedTableName(schema, "persons")} (
                      "id" uuid PRIMARY KEY
                    )
                    """.trimIndent(),
                )
                connection.execute(
                    """
                    CREATE TABLE ${qualifiedTableName(schema, "teams")} (
                      "id" uuid PRIMARY KEY,
                      "owner_id" uuid NOT NULL
                    )
                    """.trimIndent(),
                )
                val model = relationshipModel(schema)
                val migration = PostgresqlOverlay.renderMigration(model)
                val metadata = PgGraphqlOverlay.render(model)

                connection.execute(migration)
                connection.execute(migration)
                connection.execute(metadata)
                connection.execute(metadata)

                assertEquals(1, connection.foreignKeyCount(schema, "teams", "owner_id"))
                assertEquals(
                    """@graphql({"foreign_name": "owner"})""",
                    connection.constraintComment(schema, "teams", "owner_id"),
                )
            } finally {
                connection.execute("DROP SCHEMA IF EXISTS ${quoteIdentifier(schema)} CASCADE")
            }
        }
    }

    @Test
    fun `enforces every relationship shape and non-null array elements`() {
        connectionOrSkip().use { connection ->
            val schema = "viaduct_test_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                connection.execute("CREATE SCHEMA ${quoteIdentifier(schema)}")
                connection.createCompleteSchema(schema)
                val model = completeRelationshipModel(schema)
                val migration = PostgresqlOverlay.renderMigration(model)
                val metadata = PgGraphqlOverlay.render(model)

                connection.execute(migration)
                connection.execute(migration)
                connection.execute(metadata)

                connection.assertCompleteSchema(schema)
            } finally {
                connection.execute("DROP SCHEMA IF EXISTS ${quoteIdentifier(schema)} CASCADE")
            }
        }
    }

    private fun connectionOrSkip(): Connection {
        val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
        val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
        val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
        val connection = runCatching { DriverManager.getConnection(url, user, password) }.getOrNull()
        assumeTrue(
            connection != null,
            "Start local Supabase or set PG_INTEGRATION_JDBC_URL to run PostgreSQL integration tests",
        )
        return requireNotNull(connection)
    }

    private fun relationshipModel(schema: String): EffectiveHibernateModel =
        EffectiveHibernateModel(
            entities = emptyList(),
            relationships =
                listOf(
                    EffectiveHibernateRelationship(
                        ownerTypeName = "Team",
                        fieldName = "owner",
                        schemaName = schema,
                        tableName = "teams",
                        columnName = "owner_id",
                        graphqlNameKind = GraphqlNameKind.FOREIGN,
                        targetSchemaName = schema,
                        targetTableName = "persons",
                        targetIdColumnName = "id",
                    ),
                ),
            computedRelationships = emptyList(),
            arrays = emptyList(),
        )

    private fun completeRelationshipModel(schema: String): EffectiveHibernateModel =
        EffectiveHibernateModel(
            entities = emptyList(),
            relationships =
                listOf(
                    relationship(schema, "teams", "owner_id", "persons"),
                    relationship(
                        schema,
                        "teams",
                        "group_id",
                        "groups",
                        GraphqlNameKind.NONE,
                    ),
                    relationship(
                        schema,
                        "memberships",
                        "team_id",
                        "teams",
                        GraphqlNameKind.LOCAL,
                    ),
                ),
            computedRelationships =
                listOf(
                    EffectiveHibernateComputedRelationship(
                        ownerTypeName = "Group",
                        fieldName = "members",
                        owner = EffectiveHibernateTable(schema, "groups", "id"),
                        target = EffectiveHibernateTable(schema, "persons", "id"),
                        join = EffectiveHibernateJoinTable(schema, "group_members", "group_id", "person_id"),
                        edgeFields =
                            listOf(
                                EffectiveHibernateEdgeField(
                                    name = "invitedBy",
                                    columnName = "invited_by_id",
                                    sqlType = "uuid",
                                    nullable = true,
                                    targetSchemaName = schema,
                                    targetTableName = "persons",
                                    targetIdColumnName = "id",
                                ),
                            ),
                    ),
                ),
            arrays =
                listOf(
                    EffectiveHibernateArray(
                        ownerTypeName = "Team",
                        fieldName = "labels",
                        schemaName = schema,
                        tableName = "teams",
                        columnName = "labels",
                        elementNullable = false,
                    ),
                ),
        )

    private fun relationship(
        schema: String,
        table: String,
        column: String,
        targetTable: String,
        nameKind: GraphqlNameKind = GraphqlNameKind.FOREIGN,
    ): EffectiveHibernateRelationship =
        EffectiveHibernateRelationship(
            ownerTypeName = "Team",
            fieldName =
                when (column) {
                    "owner_id" -> "owner"
                    "group_id" -> "groupId"
                    "team_id" -> "memberships"
                    else -> error("Unexpected test relationship column $column")
                },
            schemaName = schema,
            tableName = table,
            columnName = column,
            graphqlNameKind = nameKind,
            targetSchemaName = schema,
            targetTableName = targetTable,
            targetIdColumnName = "id",
        )

    private fun Connection.createCompleteSchema(schema: String) {
        listOf("groups", "persons", "teams").forEach { table ->
            execute("CREATE TABLE ${qualifiedTableName(schema, table)} (\"id\" uuid PRIMARY KEY)")
        }
        execute(
            """
            ALTER TABLE ${qualifiedTableName(schema, "teams")}
              ADD COLUMN "owner_id" uuid,
              ADD COLUMN "group_id" uuid,
              ADD COLUMN "labels" text[]
            """.trimIndent(),
        )
        execute(
            "CREATE TABLE ${qualifiedTableName(schema, "memberships")} " +
                "(\"id\" uuid PRIMARY KEY, \"team_id\" uuid)",
        )
        execute(
            "CREATE TABLE ${qualifiedTableName(schema, "group_members")} " +
                "(\"group_id\" uuid, \"person_id\" uuid)",
        )
    }

    private fun Connection.assertCompleteSchema(schema: String) {
        assertEquals(1, foreignKeyCount(schema, "teams", "owner_id"))
        assertEquals(1, foreignKeyCount(schema, "teams", "group_id"))
        assertEquals(1, foreignKeyCount(schema, "memberships", "team_id"))
        assertEquals(1, foreignKeyCount(schema, "group_members", "group_id"))
        assertEquals(1, foreignKeyCount(schema, "group_members", "person_id"))
        assertEquals(1, foreignKeyCount(schema, "group_members", "invited_by_id"))
        assertNull(constraintComment(schema, "teams", "group_id"))
        assertEquals(
            """@graphql({"local_name": "memberships"})""",
            constraintComment(schema, "memberships", "team_id"),
        )
        execute(
            "INSERT INTO ${qualifiedTableName(schema, "teams")} (id, labels) " +
                "VALUES (gen_random_uuid(), ARRAY['one', 'two'])",
        )
        val failure =
            assertFailsWith<SQLException> {
                execute(
                    "INSERT INTO ${qualifiedTableName(schema, "teams")} (id, labels) " +
                        "VALUES (gen_random_uuid(), ARRAY['one', NULL])",
                )
            }
        assertEquals("23514", failure.sqlState)
    }

    private fun Connection.execute(sql: String) {
        JdbcOperations.execute(this, sql)
    }

    private fun Connection.foreignKeyCount(
        schema: String,
        table: String,
        column: String,
    ): Int =
        JdbcOperations.query(
            this,
            """
            SELECT count(*)
              FROM pg_constraint constraint_def
              JOIN pg_class table_def ON table_def.oid = constraint_def.conrelid
              JOIN pg_namespace schema_def ON schema_def.oid = table_def.relnamespace
              JOIN pg_attribute column_def
                ON column_def.attrelid = constraint_def.conrelid
               AND column_def.attnum = ANY (constraint_def.conkey)
             WHERE constraint_def.contype = 'f'
               AND schema_def.nspname = ?
               AND table_def.relname = ?
               AND column_def.attname = ?
            """.trimIndent(),
            { result ->
                check(result.next())
                result.getInt(1)
            },
            schema,
            table,
            column,
        )

    private fun Connection.constraintComment(
        schema: String,
        table: String,
        column: String,
    ): String? =
        JdbcOperations.query(
            this,
            """
            SELECT obj_description(constraint_def.oid, 'pg_constraint')
              FROM pg_constraint constraint_def
              JOIN pg_class table_def ON table_def.oid = constraint_def.conrelid
              JOIN pg_namespace schema_def ON schema_def.oid = table_def.relnamespace
              JOIN pg_attribute column_def
                ON column_def.attrelid = constraint_def.conrelid
               AND column_def.attnum = ANY (constraint_def.conkey)
             WHERE constraint_def.contype = 'f'
               AND schema_def.nspname = ?
               AND table_def.relname = ?
               AND column_def.attname = ?
            """.trimIndent(),
            { result ->
                check(result.next())
                result.getString(1)
            },
            schema,
            table,
            column,
        )
}
