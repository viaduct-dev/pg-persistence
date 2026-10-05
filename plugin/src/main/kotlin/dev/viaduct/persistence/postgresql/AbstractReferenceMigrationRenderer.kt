package dev.viaduct.persistence.postgresql

internal object AbstractReferenceMigrationRenderer : MigrationRenderer<PostgresqlMigrationOperation.AddAbstractCheck> {
    override val operationType = PostgresqlMigrationOperation.AddAbstractCheck::class

    override fun render(operation: PostgresqlMigrationOperation.AddAbstractCheck): String {
        val reference = operation.reference
        val table = qualifiedTableName(reference.schemaName, reference.tableName)
        val name =
            "${reference.tableName}_${reference.fieldName}_target_check".let {
                if (it.length <= POSTGRES_IDENTIFIER_LIMIT) {
                    it
                } else {
                    it.take(POSTGRES_IDENTIFIER_LIMIT - HASH_SUFFIX_LENGTH) + "_" +
                        it.hashCode().toUInt().toString(HEX_RADIX)
                }
            }
        val columns = reference.columns.keys.joinToString(", ", transform = ::quoteIdentifier)
        val comparison = if (reference.nullable) "<= 1" else "= 1"
        // Migration input, not a runtime overlay: changing possible types must replace the old check.
        return """
            ALTER TABLE $table DROP CONSTRAINT IF EXISTS ${quoteIdentifier(name)};
            ALTER TABLE $table ADD CONSTRAINT ${quoteIdentifier(name)} CHECK (num_nonnulls($columns) $comparison);
            """.trimIndent()
    }

    private const val POSTGRES_IDENTIFIER_LIMIT = 63
    private const val HASH_SUFFIX_LENGTH = 9
    private const val HEX_RADIX = 16
}
