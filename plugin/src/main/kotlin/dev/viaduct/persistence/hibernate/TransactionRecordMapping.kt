package dev.viaduct.persistence.hibernate

/** Library-owned storage participates in the same Hibernate snapshots and diffs as application tables. */
internal object TransactionRecordMapping {
    const val ENTITY_NAME = "PgPersistenceTransactionRecord"

    fun entity(): HbmEntityMapping =
        HbmEntityMapping(
            entityName = ENTITY_NAME,
            tableName = "transaction_record",
            schemaName = "persistence_private",
            attributes =
                listOf(
                    field("id", "string", "text", primaryKey = true),
                    field("request", "string", "jsonb"),
                    field("response", "string", "jsonb"),
                    field("completedAt", "instant", "timestamp with time zone"),
                ),
        )

    private fun field(
        name: String,
        type: String,
        sqlType: String,
        primaryKey: Boolean = false,
    ) = HbmBasicMapping(name, type, name, nullable = false, primaryKey = primaryKey, columnDefinition = sqlType)
}
