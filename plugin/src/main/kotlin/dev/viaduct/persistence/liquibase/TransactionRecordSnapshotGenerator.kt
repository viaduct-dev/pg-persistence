package dev.viaduct.persistence.liquibase

import dev.viaduct.persistence.hibernate.TransactionRecordMapping
import dev.viaduct.persistence.hibernate.schemaOrPublic
import liquibase.database.Database
import liquibase.snapshot.DatabaseSnapshot
import liquibase.snapshot.SnapshotGenerator
import liquibase.snapshot.SnapshotGeneratorChain
import liquibase.structure.DatabaseObject
import liquibase.structure.core.Catalog
import liquibase.structure.core.Schema
import liquibase.structure.core.Table

/** liquibase-hibernate flattens schemas to HIBERNATE; keep the library's private table out of public. */
class TransactionRecordSnapshotGenerator : SnapshotGenerator {
    override fun getPriority(
        objectType: Class<out DatabaseObject>,
        database: Database,
    ): Int =
        if (database is ViaductHibernateDatabase &&
            (objectType == Schema::class.java || objectType == Catalog::class.java)
        ) {
            PRIORITY
        } else {
            SnapshotGenerator.PRIORITY_NONE
        }

    override fun <T : DatabaseObject> snapshot(
        example: T,
        snapshot: DatabaseSnapshot,
        chain: SnapshotGeneratorChain,
    ): T? {
        val database = snapshot.database as? ViaductHibernateDatabase
        if (database == null || database.metadata.getEntityBinding(TransactionRecordMapping.ENTITY_NAME) == null) {
            return chain.snapshot(example, snapshot)
        }
        // Consume the remaining chain before returning: Liquibase 5 continues with lower-priority generators.
        chain.snapshot(example, snapshot)
        if (example is Schema) {
            val name = example.name
            // PostgreSQL has one catalog per connection. Do not export Hibernate's synthetic catalog.
            example.setAttribute("catalog", null)
            database.metadata
                .collectTableMappings()
                .filter { it.isPhysicalTable && it.schemaOrPublic().equals(name, ignoreCase = true) }
                .forEach { example.addDatabaseObject(Table().setName(it.name).setSchema(example)) }
        }
        return example
    }

    override fun addsTo(): Array<Class<out DatabaseObject>> = emptyArray()

    override fun replaces(): Array<Class<out SnapshotGenerator>> = emptyArray()

    private companion object {
        const val PRIORITY = 300
    }
}
