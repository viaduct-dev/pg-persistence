package dev.viaduct.persistence.hibernate

internal class HbmMappingDocument(
    entities: List<HbmEntityMapping>,
) {
    val entities: List<HbmEntityMapping> = java.util.List.copyOf(entities)
}

internal class HbmEntityMapping(
    val entityName: String,
    val tableName: String,
    val schemaName: String? = null,
    attributes: List<HbmAttributeMapping>,
) {
    val attributes: List<HbmAttributeMapping> = java.util.List.copyOf(attributes)
}

internal sealed interface HbmAttributeMapping {
    val name: String
}

internal data class HbmBasicMapping(
    override val name: String,
    val hibernateType: String,
    val columnName: String,
    val nullable: Boolean,
    val primaryKey: Boolean,
    val insertable: Boolean = true,
    val updatable: Boolean = true,
    val columnDefinition: String? = null,
    val uniqueKey: String? = null,
    val generator: String = "assigned",
) : HbmAttributeMapping

internal data class HbmToOneMapping(
    override val name: String,
    val targetEntityName: String,
    val columnName: String,
    val nullable: Boolean,
    val foreignKeyName: String,
    val uniqueKey: String? = null,
) : HbmAttributeMapping

internal data class HbmToManyMapping(
    override val name: String,
    val targetEntityName: String,
    val keyColumnName: String,
    val inverse: Boolean,
    val joinTableName: String? = null,
    val joinSchemaName: String? = null,
    val targetColumnName: String? = null,
    val targetForeignKeyName: String? = null,
) : HbmAttributeMapping {
    init {
        val manyToMany = targetColumnName != null
        require((targetForeignKeyName != null) == manyToMany) {
            "targetColumnName and targetForeignKeyName must either both be set or both be absent"
        }
        require((joinTableName != null) == manyToMany) {
            "joinTableName must be set only for many-to-many mappings"
        }
    }
}
