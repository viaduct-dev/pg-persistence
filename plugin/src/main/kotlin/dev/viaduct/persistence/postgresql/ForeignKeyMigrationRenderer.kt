package dev.viaduct.persistence.postgresql

internal data class ForeignKeySpec(
    val schemaName: String,
    val tableName: String,
    val columnName: String,
    val targetSchemaName: String,
    val targetTableName: String,
    val targetColumnName: String,
)

/** Creates the physical constraint omitted by Hibernate's dynamic-map HBM schema model. */
internal object ForeignKeyMigrationRenderer :
    MigrationRenderer<PostgresqlMigrationOperation.AddForeignKey> {
    private const val POSTGRESQL_IDENTIFIER_LIMIT = 63
    override val operationType = PostgresqlMigrationOperation.AddForeignKey::class

    override fun render(operation: PostgresqlMigrationOperation.AddForeignKey): String = render(operation.foreignKey)

    fun render(foreignKey: ForeignKeySpec): String =
        foreignKey.run {
            val constraintName = constraintName(tableName, columnName)
            """
            DO ${'$'}viaduct_foreign_key${'$'}
            BEGIN
              IF EXISTS (
                SELECT 1 FROM pg_constraint constraint_def
                 WHERE constraint_def.contype = 'f'
                   AND constraint_def.conrelid = ${quoteLiteral(qualifiedTableName(schemaName, tableName))}::regclass
                   AND (SELECT attnum FROM pg_attribute
                         WHERE attrelid = constraint_def.conrelid AND attname = ${quoteLiteral(columnName)})
                       = ANY (constraint_def.conkey)
                   AND NOT (constraint_def.conrelid = ${quoteLiteral(qualifiedTableName(schemaName, tableName))}::regclass
                   AND constraint_def.conkey = ARRAY[
                       (SELECT attnum FROM pg_attribute
                         WHERE attrelid = constraint_def.conrelid AND attname = ${quoteLiteral(columnName)})
                   ]::smallint[]
                   AND constraint_def.confrelid = ${quoteLiteral(qualifiedTableName(targetSchemaName, targetTableName))}::regclass
                   AND constraint_def.confkey = ARRAY[
                       (SELECT attnum FROM pg_attribute
                         WHERE attrelid = constraint_def.confrelid AND attname = ${quoteLiteral(targetColumnName)})
                   ]::smallint[]
                   AND constraint_def.confupdtype = 'a' AND constraint_def.confdeltype = 'a'
                   AND constraint_def.confmatchtype = 's'
                   AND NOT constraint_def.condeferrable AND constraint_def.convalidated)
              ) THEN
                RAISE EXCEPTION 'Foreign key definition differs from the desired model; apply a reviewed migration first';
              END IF;
              IF NOT EXISTS (
                SELECT 1 FROM pg_constraint constraint_def
                 WHERE constraint_def.contype = 'f'
                   AND constraint_def.conrelid = ${quoteLiteral(qualifiedTableName(schemaName, tableName))}::regclass
                   AND constraint_def.conkey = ARRAY[
                       (SELECT attnum FROM pg_attribute
                         WHERE attrelid = constraint_def.conrelid AND attname = ${quoteLiteral(columnName)})
                   ]::smallint[]
                   AND constraint_def.confrelid = ${quoteLiteral(qualifiedTableName(targetSchemaName, targetTableName))}::regclass
                   AND constraint_def.confkey = ARRAY[
                       (SELECT attnum FROM pg_attribute
                         WHERE attrelid = constraint_def.confrelid AND attname = ${quoteLiteral(targetColumnName)})
                   ]::smallint[]
                   AND constraint_def.confupdtype = 'a' AND constraint_def.confdeltype = 'a'
                   AND constraint_def.confmatchtype = 's'
                   AND NOT constraint_def.condeferrable AND constraint_def.convalidated
              ) THEN
                ALTER TABLE ${qualifiedTableName(schemaName, tableName)}
                  ADD CONSTRAINT ${quoteIdentifier(constraintName)}
                  FOREIGN KEY (${quoteIdentifier(columnName)})
                  REFERENCES ${qualifiedTableName(targetSchemaName, targetTableName)} (${quoteIdentifier(targetColumnName)});
              END IF;
            END
            ${'$'}viaduct_foreign_key${'$'};
            """.trimIndent()
        }

    private fun constraintName(
        tableName: String,
        columnName: String,
    ): String {
        val suffix = "_${columnName}_fkey"
        val maximumTableLength = (POSTGRESQL_IDENTIFIER_LIMIT - suffix.length).coerceAtLeast(0)
        return tableName.take(maximumTableLength) + suffix
    }
}
