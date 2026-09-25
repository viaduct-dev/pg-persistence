package dev.viaduct.persistence.liquibase.pggraphql

import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.liquibase.ViaductHibernateDatabase
import liquibase.database.Database
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.DatabaseException
import liquibase.snapshot.DatabaseSnapshot
import liquibase.snapshot.InvalidExampleException
import liquibase.snapshot.SnapshotGenerator
import liquibase.snapshot.SnapshotGeneratorChain
import liquibase.structure.DatabaseObject
import liquibase.structure.core.ForeignKey

/** The [DatabaseObject] attribute holding a foreign key's pg_graphql `@graphql({...})` comment. */
const val PG_GRAPHQL_COMMENT_ATTRIBUTE = "pgGraphqlComment"

/**
 * Priority used for all three pg_graphql comment extension points (this generator, its
 * [dev.viaduct.persistence.liquibase.pggraphql.PgGraphqlCommentComparator], and its
 * [dev.viaduct.persistence.liquibase.pggraphql.PgGraphqlCommentChangeGenerator]).
 *
 * Must exceed `liquibase.ext.hibernate.snapshot.HibernateSnapshotGenerator.PRIORITY_HIBERNATE_ADDITIONAL`
 * (200): that generator returns its result directly without delegating to the chain once it
 * successfully snapshots an object, so anything registered below it never runs for a top-level
 * [ForeignKey] on a Hibernate reference database.
 */
internal const val PG_GRAPHQL_COMMENT_PRIORITY = 300

/**
 * Adds the desired/actual pg_graphql relationship-naming comment onto every snapshotted
 * [ForeignKey], sourced from the [ViaductHibernateDatabase]'s effective model on the reference
 * side, and from `pg_constraint`'s catalog comment on a real target database.
 */
class PgGraphqlCommentSnapshotGenerator : SnapshotGenerator {
    override fun getPriority(
        objectType: Class<out DatabaseObject>,
        database: Database,
    ): Int =
        if (ForeignKey::class.java.isAssignableFrom(objectType)) {
            PG_GRAPHQL_COMMENT_PRIORITY
        } else {
            SnapshotGenerator.PRIORITY_NONE
        }

    @Throws(DatabaseException::class, InvalidExampleException::class)
    override fun <T : DatabaseObject> snapshot(
        example: T,
        snapshot: DatabaseSnapshot,
        chain: SnapshotGeneratorChain,
    ): T? {
        val result = chain.snapshot(example, snapshot)
        val foreignKey = result as? ForeignKey ?: return result
        foreignKey.setAttribute(PG_GRAPHQL_COMMENT_ATTRIBUTE, readComment(foreignKey, snapshot.database))
        return result
    }

    override fun addsTo(): Array<Class<out DatabaseObject>> = arrayOf(ForeignKey::class.java)

    override fun replaces(): Array<Class<out SnapshotGenerator>> = emptyArray()

    private fun readComment(
        foreignKey: ForeignKey,
        database: Database,
    ): String? {
        val table = foreignKey.foreignKeyTable
        val tableName = table?.name
        val columnName = foreignKey.foreignKeyColumns?.firstOrNull()?.name
        if (table == null || tableName == null || columnName == null) return null
        val schemaName = table.schema?.name ?: "public"
        return if (database is ViaductHibernateDatabase) {
            database.pgGraphqlConstraintComment(schemaName, tableName, columnName)
        } else {
            readLiveComment(database, schemaName, tableName, columnName)
        }
    }

    private fun readLiveComment(
        database: Database,
        schemaName: String,
        tableName: String,
        columnName: String,
    ): String? {
        val connection = database.connection as? JdbcConnection ?: return null
        return runCatching {
            queryConstraintComment(connection, schemaName, tableName, columnName)
        }.getOrNull()
    }

    private fun queryConstraintComment(
        connection: JdbcConnection,
        schemaName: String,
        tableName: String,
        columnName: String,
    ): String? {
        val sql =
            """
            SELECT obj_description(constraint_def.oid, 'pg_constraint')
              FROM pg_constraint constraint_def
              JOIN pg_attribute column_def
                ON column_def.attrelid = constraint_def.conrelid
               AND column_def.attnum = ANY (constraint_def.conkey)
             WHERE constraint_def.contype = 'f'
               AND constraint_def.conrelid = ?::regclass
               AND column_def.attname = ?
             LIMIT 1
            """.trimIndent()
        return JdbcOperations.query(
            connection.underlyingConnection,
            sql,
            { rows -> if (rows.next()) rows.getString(1) else null },
            "\"$schemaName\".\"$tableName\"",
            columnName,
        )
    }
}
