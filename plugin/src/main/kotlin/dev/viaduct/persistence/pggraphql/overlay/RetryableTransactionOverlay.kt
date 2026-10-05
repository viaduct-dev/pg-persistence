package dev.viaduct.persistence.pggraphql.overlay

import dev.viaduct.persistence.hibernate.TransactionRecordMapping
import dev.viaduct.persistence.hibernate.requiredProperty
import dev.viaduct.persistence.hibernate.schemaOrPublic
import dev.viaduct.persistence.hibernate.singleColumnName
import org.hibernate.boot.Metadata
import org.stringtemplate.v4.ST

/** Only functions and grants are SQL; Hibernate owns the record table and its columns. */
internal object RetryableTransactionOverlay {
    fun prerequisites(metadata: Metadata): String =
        metadata
            .getEntityBinding(TransactionRecordMapping.ENTITY_NAME)
            ?.let {
                "CREATE SCHEMA IF NOT EXISTS ${quoteIdentifier(it.table.schemaOrPublic())};\n"
            }.orEmpty()

    fun render(metadata: Metadata): String {
        val binding = metadata.getEntityBinding(TransactionRecordMapping.ENTITY_NAME) ?: return ""
        val schema = binding.table.schemaOrPublic()
        require(schema != "public" && schema != "graphql_public") {
            "Transaction records must be mapped outside API-exposed schemas"
        }
        val source =
            requireNotNull(javaClass.getResourceAsStream(RESOURCE))
                .bufferedReader()
                .use { it.readText() }
        val template = ST(source, '~', '~')
        template.add("schema", quoteIdentifier(schema))
        template.add("table", "${quoteIdentifier(schema)}.${quoteIdentifier(binding.table.name)}")
        listOf("id", "request", "response", "completedAt").forEach { property ->
            val column = binding.requiredProperty(binding.entityName, property).singleColumnName()
            template.add(property, quoteIdentifier(column))
        }
        return template.render()
    }

    private const val RESOURCE = "/dev/viaduct/persistence/pggraphql/retryable-transactions.stg"
}
