package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateComputedRelationship
import dev.viaduct.persistence.hibernate.EffectiveHibernateModel

/** Renders schemas required by computed association functions. */
internal object PostgresqlPrerequisiteRenderer {
    fun render(model: EffectiveHibernateModel): String =
        (
            model.entities.map { it.schemaName }.filter { it != "public" } +
                model.computedRelationships.map(EffectiveHibernateComputedRelationship::joinSchemaName)
        ).distinct()
            .sorted()
            .joinToString(separator = "\n", postfix = "\n") {
                "CREATE SCHEMA IF NOT EXISTS ${quoteIdentifier(it)};"
            }
}
