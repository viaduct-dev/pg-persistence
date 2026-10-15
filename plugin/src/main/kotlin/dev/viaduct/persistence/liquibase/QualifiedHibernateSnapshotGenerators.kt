package dev.viaduct.persistence.liquibase

import dev.viaduct.persistence.hibernate.schemaOrPublic
import liquibase.ext.hibernate.snapshot.ColumnSnapshotGenerator
import liquibase.ext.hibernate.snapshot.ForeignKeySnapshotGenerator
import liquibase.ext.hibernate.snapshot.IndexSnapshotGenerator
import liquibase.ext.hibernate.snapshot.PrimaryKeySnapshotGenerator
import liquibase.ext.hibernate.snapshot.SchemaSnapshotGenerator
import liquibase.ext.hibernate.snapshot.TableSnapshotGenerator
import liquibase.ext.hibernate.snapshot.UniqueConstraintSnapshotGenerator
import liquibase.snapshot.DatabaseSnapshot
import liquibase.snapshot.SnapshotGenerator
import liquibase.structure.DatabaseObject
import liquibase.structure.core.Column
import liquibase.structure.core.ForeignKey
import liquibase.structure.core.ForeignKeyConstraintType
import liquibase.structure.core.Schema
import liquibase.structure.core.Table
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.dialect.PostgreSQLDialect

/** Keep Liquibase Hibernate's normal type/constraint projection, but resolve qualified identities. */
class QualifiedHibernateSchemaSnapshotGenerator : SchemaSnapshotGenerator() {
    override fun snapshotObject(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): DatabaseObject =
        if (snapshot.qualifiedDatabase() != null) {
            example.apply { setAttribute("catalog", null) }
        } else {
            super.snapshotObject(example, snapshot)
        }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(SchemaSnapshotGenerator::class.java, liquibase.snapshot.jvm.SchemaSnapshotGenerator::class.java)
}

class QualifiedHibernateTableSnapshotGenerator : TableSnapshotGenerator() {
    override fun findHibernateTable(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): org.hibernate.mapping.Table? =
        if (snapshot.qualifiedDatabase() != null) {
            qualifiedTable(example, snapshot)
        } else {
            super.findHibernateTable(example, snapshot)
        }

    override fun snapshotObject(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): DatabaseObject {
        val table = super.snapshotObject(example, snapshot)
        if (table is Table) {
            snapshot
                .qualifiedDatabase()
                ?.pgGraphqlTableComment(table.schema.name, table.name)
                ?.let { table.remarks = it }
        }
        return table
    }

    override fun addTo(
        foundObject: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ) {
        val database = snapshot.qualifiedDatabase()
        if (database == null) {
            super.addTo(foundObject, snapshot)
        } else if (foundObject is Schema && snapshot.snapshotControl.shouldInclude(Table::class.java)) {
            database.metadata
                .collectTableMappings()
                .filter { it.isPhysicalTable && it.schemaOrPublic() == foundObject.name }
                .forEach { table ->
                    val example = Table().setName(table.name).setSchema(foundObject)
                    foundObject.addDatabaseObject(snapshotObject(example, snapshot))
                }
        }
    }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(TableSnapshotGenerator::class.java, liquibase.snapshot.jvm.TableSnapshotGenerator::class.java)
}

class QualifiedHibernateColumnSnapshotGenerator : ColumnSnapshotGenerator() {
    override fun findHibernateTable(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): org.hibernate.mapping.Table? =
        if (snapshot.qualifiedDatabase() != null) {
            qualifiedTable(example, snapshot)
        } else {
            super.findHibernateTable(example, snapshot)
        }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(ColumnSnapshotGenerator::class.java, liquibase.snapshot.jvm.ColumnSnapshotGenerator::class.java)
}

class QualifiedHibernateIndexSnapshotGenerator : IndexSnapshotGenerator() {
    override fun findHibernateTable(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): org.hibernate.mapping.Table? =
        if (snapshot.qualifiedDatabase() != null) {
            qualifiedTable(example, snapshot)
        } else {
            super.findHibernateTable(example, snapshot)
        }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(IndexSnapshotGenerator::class.java, liquibase.snapshot.jvm.IndexSnapshotGenerator::class.java)
}

class QualifiedHibernatePrimaryKeySnapshotGenerator : PrimaryKeySnapshotGenerator() {
    override fun findHibernateTable(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): org.hibernate.mapping.Table? =
        if (snapshot.qualifiedDatabase() != null) {
            qualifiedTable(example, snapshot)
        } else {
            super.findHibernateTable(example, snapshot)
        }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(PrimaryKeySnapshotGenerator::class.java, liquibase.snapshot.jvm.PrimaryKeySnapshotGenerator::class.java)
}

class QualifiedHibernateUniqueConstraintSnapshotGenerator : UniqueConstraintSnapshotGenerator() {
    override fun findHibernateTable(
        example: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ): org.hibernate.mapping.Table? =
        if (snapshot.qualifiedDatabase() != null) {
            qualifiedTable(example, snapshot)
        } else {
            super.findHibernateTable(example, snapshot)
        }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(
            UniqueConstraintSnapshotGenerator::class.java,
            liquibase.snapshot.jvm.UniqueConstraintSnapshotGenerator::class.java,
        )
}

class QualifiedHibernateForeignKeySnapshotGenerator : ForeignKeySnapshotGenerator() {
    override fun addTo(
        foundObject: DatabaseObject,
        snapshot: DatabaseSnapshot,
    ) {
        if (snapshot.qualifiedDatabase() == null) {
            super.addTo(foundObject, snapshot)
            return
        }
        if (foundObject !is Table || !snapshot.snapshotControl.shouldInclude(ForeignKey::class.java)) return
        val table = foundObject
        qualifiedTable(table, snapshot)
            ?.foreignKeyCollection
            ?.filter { it.isCreationEnabled && it.isPhysicalConstraint }
            ?.forEach { mapping ->
                val foreignKey = qualifiedForeignKey(mapping, table, snapshot)
                table.outgoingForeignKeys.add(foreignKey)
                table.schema.addDatabaseObject(foreignKey)
            }
    }

    override fun replaces(): Array<Class<out SnapshotGenerator>> =
        arrayOf(ForeignKeySnapshotGenerator::class.java, liquibase.snapshot.jvm.ForeignKeySnapshotGenerator::class.java)
}

private fun qualifiedForeignKey(
    mapping: org.hibernate.mapping.ForeignKey,
    table: Table,
    snapshot: DatabaseSnapshot,
): ForeignKey {
    val target = mapping.referencedTable
    return ForeignKey().apply {
        name = constraintName(mapping.name, snapshot)
        foreignKeyTable = table
        primaryKeyTable =
            Table().apply {
                name = target.name
                setSchema(null, target.schemaOrPublic())
            }
        mapping.columns.forEach { addForeignKeyColumn(Column(it.name)) }
        (mapping.referencedColumns.takeUnless { it.isEmpty() } ?: target.primaryKey.columns)
            .forEach { addPrimaryKeyColumn(Column(it.name)) }
        setUpdateRule(ForeignKeyConstraintType.importedKeyNoAction)
        setDeleteRule(deleteRule(mapping))
        setDeferrable(false)
        setInitiallyDeferred(false)
    }
}

private fun constraintName(
    name: String,
    snapshot: DatabaseSnapshot,
): String {
    val identifier = Identifier.toIdentifier(name)
    return if (!identifier.isQuoted && snapshot.qualifiedDatabase()?.dialect is PostgreSQLDialect) {
        identifier.text.lowercase(java.util.Locale.ROOT)
    } else {
        identifier.text
    }
}

private fun deleteRule(mapping: org.hibernate.mapping.ForeignKey): ForeignKeyConstraintType =
    when (mapping.onDeleteAction?.name) {
        "CASCADE" -> ForeignKeyConstraintType.importedKeyCascade
        "RESTRICT" -> ForeignKeyConstraintType.importedKeyRestrict
        "SET_NULL" -> ForeignKeyConstraintType.importedKeySetNull
        "SET_DEFAULT" -> ForeignKeyConstraintType.importedKeySetDefault
        else -> ForeignKeyConstraintType.importedKeyNoAction
    }

private fun DatabaseSnapshot.qualifiedDatabase(): ViaductHibernateDatabase? =
    (database as? ViaductHibernateDatabase)?.takeIf { it.preservesSchemas }

private fun qualifiedTable(
    example: DatabaseObject,
    snapshot: DatabaseSnapshot,
): org.hibernate.mapping.Table? =
    snapshot.qualifiedDatabase()?.metadata?.collectTableMappings()?.singleOrNull {
        it.isPhysicalTable && it.name == example.name && it.schemaOrPublic() == example.schema?.name
    }
