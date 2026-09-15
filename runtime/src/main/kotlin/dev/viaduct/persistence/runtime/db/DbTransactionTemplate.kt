package dev.viaduct.persistence.runtime.db

import org.stringtemplate.v4.STGroupString

/** Renders the fixed GraphQL syntax for a buffered transaction. */
internal object DbTransactionTemplate {
    private const val RESOURCE = "/dev/viaduct/persistence/runtime/db/db-transaction.stg"
    private val templates = STGroupString(load())

    fun transaction(
        definitions: List<String>,
        fields: List<String>,
    ): String =
        templates
            .getInstanceOf("transaction")
            .add("definitions", definitions)
            .add("fields", fields)
            .render()

    fun insert(
        prefix: String,
        field: String,
    ): String = operation("insert", prefix, field)

    fun update(
        prefix: String,
        field: String,
    ): String = operation("update", prefix, field)

    fun delete(
        prefix: String,
        field: String,
    ): String = operation("delete", prefix, field)

    private fun operation(
        templateName: String,
        prefix: String,
        field: String,
    ): String =
        templates
            .getInstanceOf(templateName)
            .add("prefix", prefix)
            .add("field", field)
            .render()

    private fun load(): String =
        requireNotNull(DbTransactionTemplate::class.java.getResourceAsStream(RESOURCE)) {
            "Missing transaction template $RESOURCE"
        }.bufferedReader().use { it.readText() }
}
