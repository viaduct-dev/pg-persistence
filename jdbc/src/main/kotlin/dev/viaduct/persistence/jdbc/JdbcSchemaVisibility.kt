package dev.viaduct.persistence.jdbc

import java.sql.Connection

/**
 * Exposes a caller-authorized set of PostgreSQL schemas together for a whole transaction.
 *
 * Schema visibility is not authorization: PostgreSQL roles, grants, and RLS still apply. Supply
 * trusted role/JWT setup explicitly; request headers never choose schemas automatically. Settings
 * are transaction-local, so commit and rollback restore the connection's previous search path.
 */
class JdbcSchemaVisibility(
    schemas: Collection<String>,
    private val requestSetup: JdbcRequestSetup =
        JdbcRequestSetup { _, headers ->
            require(headers.isEmpty()) { "Configure trusted request setup to interpret JDBC headers" }
        },
) : JdbcRequestSetup {
    private val searchPath: String

    init {
        val names = schemas.toList()
        require(names.isNotEmpty()) { "Schema visibility requires at least one explicit schema" }
        names.forEach { schema ->
            require(
                schema.isNotBlank() &&
                    '\u0000' !in schema &&
                    schema != "\$user" &&
                    schema != "pg_temp" &&
                    schema.toByteArray(Charsets.UTF_8).size <= MAX_IDENTIFIER_BYTES,
            ) { "Invalid explicit PostgreSQL schema name: $schema" }
        }
        // Quote each identifier and bind the whole value; never interpolate schema names as SQL.
        searchPath = names.toSortedSet().joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
    }

    override fun configure(
        connection: Connection,
        headers: Map<String, String>,
    ) {
        check(!connection.autoCommit) { "Schema visibility requires an open transaction" }
        requestSetup.configure(connection, headers)
        check(!connection.autoCommit) { "Schema visibility requires an open transaction" }
        GraphqlJdbcConnection.schemaVisibility(connection, searchPath)
    }

    private companion object {
        const val MAX_IDENTIFIER_BYTES = 63
    }
}
