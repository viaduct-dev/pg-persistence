package dev.viaduct.persistence.model

import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import viaduct.graphql.schema.ViaductSchema

/** One stored field, whether its declared target is concrete, a union, or an interface. */
internal data class PersistenceRelationship(
    val ownerType: String,
    val fieldName: String,
    val declaredType: ViaductSchema.TypeDef,
    val collection: Boolean,
    val nullable: Boolean,
    val edgeTypeName: String? = null,
    val connectionTypeName: String? = null,
    val idOfDirected: Boolean = false,
) {
    val targetName: String get() = declaredType.name
    val isAbstract: Boolean = declaredType.isAbstract
    val targets: Set<String> =
        java.util.Collections.unmodifiableSet(
            if (isAbstract) declaredType.possibleObjectTypes.mapTo(sortedSetOf()) { it.name } else setOf(targetName),
        )
    val coordinate: String get() = "$ownerType.$fieldName"
    val associationType: String get() = requireNotNull(mapping).rowType

    fun targetAttributes(): List<PersistenceToOneAttribute> =
        targets.map { target ->
            PersistenceToOneAttribute(
                name = mapping?.targetField(target) ?: fieldName,
                nullable = isAbstract || nullable,
                targetTypeName = target,
                idOfDirected = idOfDirected,
            )
        }

    fun abstractMapping(): AbstractRelationship? = mapping

    private val mapping: AbstractRelationship? =
        if (!isAbstract) {
            null
        } else {
            AbstractRelationship(
                ownerType,
                fieldName,
                targetName,
                targets,
                nullable = !collection && nullable,
                collection = collection,
                connectionType = connectionTypeName,
                edgeType = edgeTypeName,
            )
        }
}

internal val ViaductSchema.TypeDef.isAbstract: Boolean
    get() = this is ViaductSchema.Union || this is ViaductSchema.Interface
