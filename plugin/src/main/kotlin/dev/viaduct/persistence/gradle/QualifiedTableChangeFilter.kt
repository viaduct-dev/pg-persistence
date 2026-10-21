package dev.viaduct.persistence.gradle

import liquibase.diff.output.StandardObjectChangeFilter
import liquibase.structure.DatabaseObject
import liquibase.structure.core.Catalog
import liquibase.structure.core.Column
import liquibase.structure.core.Data
import liquibase.structure.core.ForeignKey
import liquibase.structure.core.Index
import liquibase.structure.core.PrimaryKey
import liquibase.structure.core.Schema
import liquibase.structure.core.Table
import liquibase.structure.core.UniqueConstraint

/** Child changes belong to their source table, including foreign keys to another schema. */
internal class QualifiedTableChangeFilter(
    tables: Set<Pair<String, String>>,
) : StandardObjectChangeFilter(FilterType.INCLUDE, "table:.*") {
    private val tables = tables.toSet()

    override fun include(databaseObject: DatabaseObject?): Boolean =
        when (databaseObject) {
            is Table -> (databaseObject.schema?.name to databaseObject.name) in tables
            is Column -> include(databaseObject.relation)
            is Index -> include(databaseObject.relation)
            is ForeignKey -> include(databaseObject.foreignKeyTable)
            is PrimaryKey -> include(databaseObject.table)
            is UniqueConstraint -> include(databaseObject.relation)
            is Data -> include(databaseObject.table)
            is Schema -> tables.any { it.first == databaseObject.name }
            is Catalog -> true
            else -> false
        }
}
