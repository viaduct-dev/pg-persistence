package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfiguration
import liquibase.command.CommandScope

/** Retain the existing single-schema defaults unless private transaction storage is enabled. */
internal fun CommandScope.includeMappedSchemas(
    configuration: HibernateMetadataConfiguration,
    comparison: Boolean = false,
): CommandScope =
    apply {
        if (configuration.semanticModel?.retryableTransactions == true) {
            val schemas = HibernateMetadataBootstrap.schemaNames(configuration)
            addArgumentValue("schemas", schemas)
            if (comparison) {
                addArgumentValue("referenceSchemas", schemas)
                addArgumentValue("includeSchema", true)
            }
        }
    }
