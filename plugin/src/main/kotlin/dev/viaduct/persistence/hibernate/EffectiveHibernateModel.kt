package dev.viaduct.persistence.hibernate

class EffectiveHibernateModel(
    entities: List<EffectiveHibernateEntity>,
    relationships: List<EffectiveHibernateRelationship>,
    computedRelationships: List<EffectiveHibernateComputedRelationship>,
    arrays: List<EffectiveHibernateArray>,
    abstractReferences: List<EffectiveAbstractReference> = emptyList(),
) {
    val entities: List<EffectiveHibernateEntity> = java.util.List.copyOf(entities)
    val relationships: List<EffectiveHibernateRelationship> = java.util.List.copyOf(relationships)
    val computedRelationships: List<EffectiveHibernateComputedRelationship> =
        java.util.List.copyOf(computedRelationships)
    val arrays: List<EffectiveHibernateArray> = java.util.List.copyOf(arrays)
    val abstractReferences: List<EffectiveAbstractReference> = java.util.List.copyOf(abstractReferences)

    override fun equals(other: Any?): Boolean =
        other is EffectiveHibernateModel &&
            entities == other.entities &&
            relationships == other.relationships &&
            computedRelationships == other.computedRelationships &&
            arrays == other.arrays &&
            abstractReferences == other.abstractReferences

    override fun hashCode(): Int {
        var result = entities.hashCode()
        result = 31 * result + relationships.hashCode()
        result = 31 * result + computedRelationships.hashCode()
        result = 31 * result + arrays.hashCode()
        result = 31 * result + abstractReferences.hashCode()
        return result
    }

    override fun toString(): String =
        "EffectiveHibernateModel(" +
            "entities=$entities, " +
            "relationships=$relationships, " +
            "computedRelationships=$computedRelationships, " +
            "arrays=$arrays)"
}

class EffectiveAbstractReference(
    val schemaName: String,
    val tableName: String,
    val fieldName: String,
    val nullable: Boolean,
    /** Physical columns mapped to stable pg_graphql input field names. */
    columns: Map<String, String>,
    val ownerIdColumnName: String? = null,
) {
    val columns: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(columns))

    @Suppress("LongParameterList") // Preserve the existing data-class copy API.
    fun copy(
        schemaName: String = this.schemaName,
        tableName: String = this.tableName,
        fieldName: String = this.fieldName,
        nullable: Boolean = this.nullable,
        columns: Map<String, String> = this.columns,
        ownerIdColumnName: String? = this.ownerIdColumnName,
    ): EffectiveAbstractReference =
        EffectiveAbstractReference(
            schemaName,
            tableName,
            fieldName,
            nullable,
            columns,
            ownerIdColumnName,
        )

    operator fun component1(): String = schemaName

    operator fun component2(): String = tableName

    operator fun component3(): String = fieldName

    operator fun component4(): Boolean = nullable

    operator fun component5(): Map<String, String> = columns

    operator fun component6(): String? = ownerIdColumnName

    override fun equals(other: Any?): Boolean =
        other is EffectiveAbstractReference &&
            schemaName == other.schemaName &&
            tableName == other.tableName &&
            fieldName == other.fieldName &&
            nullable == other.nullable &&
            columns == other.columns &&
            ownerIdColumnName == other.ownerIdColumnName

    override fun hashCode(): Int {
        var result = schemaName.hashCode()
        result = 31 * result + tableName.hashCode()
        result = 31 * result + fieldName.hashCode()
        result = 31 * result + nullable.hashCode()
        result = 31 * result + columns.hashCode()
        result = 31 * result + (ownerIdColumnName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "EffectiveAbstractReference(" +
            "schemaName=$schemaName, " +
            "tableName=$tableName, " +
            "fieldName=$fieldName, " +
            "nullable=$nullable, " +
            "columns=$columns, " +
            "ownerIdColumnName=$ownerIdColumnName)"
}

data class EffectiveHibernateEntity(
    val graphqlName: String,
    val schemaName: String,
    val tableName: String,
    val generatedGlobalId: Boolean,
    val internalIdColumnName: String?,
    val globalIdColumnName: String?,
)

data class EffectiveHibernateRelationship(
    val ownerTypeName: String,
    val fieldName: String,
    val schemaName: String,
    val tableName: String,
    val columnName: String,
    val graphqlNameKind: GraphqlNameKind,
    /** Populated for [GraphqlNameKind.FOREIGN]: the table the foreign key points at. */
    val targetSchemaName: String? = null,
    val targetTableName: String? = null,
    val targetIdColumnName: String? = null,
)

data class EffectiveHibernateArray(
    val ownerTypeName: String,
    val fieldName: String,
    val schemaName: String,
    val tableName: String,
    val columnName: String,
    val elementNullable: Boolean,
)

class EffectiveHibernateComputedRelationship(
    val ownerTypeName: String,
    val fieldName: String,
    val owner: EffectiveHibernateTable,
    val target: EffectiveHibernateTable,
    val join: EffectiveHibernateJoinTable,
    edgeFields: List<EffectiveHibernateEdgeField> = emptyList(),
) {
    val edgeFields: List<EffectiveHibernateEdgeField> = java.util.List.copyOf(edgeFields)

    val ownerSchemaName: String get() = owner.schemaName
    val ownerTableName: String get() = owner.tableName
    val ownerIdColumnName: String get() = owner.idColumnName
    val targetSchemaName: String get() = target.schemaName
    val targetTableName: String get() = target.tableName
    val targetIdColumnName: String get() = target.idColumnName
    val joinSchemaName: String get() = join.schemaName
    val joinTableName: String get() = join.tableName
    val joinOwnerColumnName: String get() = join.ownerColumnName
    val joinTargetColumnName: String get() = join.targetColumnName

    override fun equals(other: Any?): Boolean =
        other is EffectiveHibernateComputedRelationship &&
            ownerTypeName == other.ownerTypeName &&
            fieldName == other.fieldName &&
            owner == other.owner &&
            target == other.target &&
            join == other.join &&
            edgeFields == other.edgeFields

    override fun hashCode(): Int =
        listOf(
            ownerTypeName,
            fieldName,
            owner,
            target,
            join,
            edgeFields,
        ).hashCode()
}

data class EffectiveHibernateTable(
    val schemaName: String,
    val tableName: String,
    val idColumnName: String,
)

data class EffectiveHibernateJoinTable(
    val schemaName: String,
    val tableName: String,
    val ownerColumnName: String,
    val targetColumnName: String,
)

data class EffectiveHibernateEdgeField(
    val name: String,
    val columnName: String,
    val sqlType: String,
    val nullable: Boolean,
    val targetSchemaName: String? = null,
    val targetTableName: String? = null,
    val targetIdColumnName: String? = null,
    val collection: EffectiveHibernateEdgeCollection? = null,
)

data class EffectiveHibernateEdgeCollection(
    val target: EffectiveHibernateTable,
    val ownerColumnName: String,
    val join: EffectiveHibernateJoinTable? = null,
)

enum class GraphqlNameKind {
    FOREIGN,
    LOCAL,
    NONE,
}
