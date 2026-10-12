package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import org.hibernate.boot.Metadata
import org.hibernate.mapping.Column
import java.security.MessageDigest

/** Attach native Hibernate indexes after physical naming, retaining the authored column order. */
internal fun configureSecondaryIndexes(
    metadata: Metadata,
    model: PersistenceModel?,
) {
    model?.entities?.forEach { entity ->
        val mapping = metadata.getEntityBinding(entity.graphqlName)
        entity.indexes.forEach { fields ->
            val columns =
                fields.map { field ->
                    val column = mapping.getProperty(field).columns.singleOrNull()
                    require(column is Column && mapping.table.containsColumn(column)) {
                        "types.${entity.graphqlName}.indexes field '$field' must map to one physical table column"
                    }
                    column
                }
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest((entity.graphqlName + ":" + fields.joinToString(",")).toByteArray())
                    .take(INDEX_DIGEST_BYTES)
                    .joinToString("") { "%02x".format(it) }
            val index = mapping.table.getOrCreateIndex("IX_$digest")
            require(!index.isUnique && (index.selectables.isEmpty() || index.selectables == columns)) {
                "Conflicting secondary index ${index.name}"
            }
            if (index.selectables.isEmpty()) columns.forEach(index::addColumn)
        }
    }
}

private const val INDEX_DIGEST_BYTES = 8
