package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.jdbc.JdbcOperations
import org.hibernate.boot.Metadata
import java.sql.Connection

/** A missing destination with a same-named source is a migration review, never a fresh table. */
internal object SchemaPlacementMigrationGuard {
    fun verify(
        metadata: Metadata,
        connection: Connection,
    ) {
        if (connection.metaData.databaseProductName != "PostgreSQL") return
        val existing =
            JdbcOperations.query(
                connection,
                """
                SELECT n.nspname, c.relname FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p') AND n.nspname <> 'information_schema'
                  AND n.nspname !~ '^pg_'
                """.trimIndent(),
                { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString(1) to rows.getString(2))
                    }
                },
            )
        val moves =
            metadata
                .collectTableMappings()
                .filter { it.isPhysicalTable }
                .mapNotNull { table ->
                    val destination = (table.schema ?: "public") to table.name
                    val sources =
                        existing
                            .filter { it.second == table.name && it.first != destination.first }
                            .sortedBy { it.first }
                    if (destination !in existing && sources.isNotEmpty()) {
                        val sourceNames = sources.joinToString { "${it.first}.${it.second}" }
                        "$sourceNames -> ${destination.first}.${destination.second}"
                    } else {
                        null
                    }
                }.sorted()
        require(moves.isEmpty()) {
            "Possible database schema move: ${moves.joinToString("; ")}. " +
                "Apply an explicit reviewed data-preserving migration before generating a schema diff. " +
                "Automatic creation of replacement tables is refused."
        }
    }
}
