package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.runtime.reflection.GeneratedFieldReflection
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type

/** Applies the persistence model's deterministic rules to reflected connection types. */
internal class ConnectionStorageClassifier(
    private val reflection: GeneratedFieldReflection,
    private val legacyCollectionNodeType: (Type<*>) -> Type<*>? = { null },
) {
    fun isAssociationBacked(
        ownerType: Type<*>,
        connectionType: Type<*>,
    ): Boolean {
        val targetType = reflection.structuralConnectionNodeType(connectionType) ?: return false
        return hasPersistedEdgeFields(connectionType) ||
            collectionCount(ownerType, targetType) > 1 ||
            collectionCount(targetType, ownerType) == 1
    }

    private fun hasPersistedEdgeFields(connectionType: Type<*>): Boolean {
        val edgeType = reflection.field(connectionType, "edges")?.type ?: return false
        return reflection.allFields(edgeType).any { !isStructuralField(it.name) }
    }

    private fun collectionCount(
        ownerType: Type<*>,
        targetType: Type<*>,
    ): Int =
        reflection.allFields(ownerType).count { field ->
            val composite = field as? CompositeField<*, *> ?: return@count false
            collectionNodeType(composite.type)?.name == targetType.name
        }

    private fun collectionNodeType(type: Type<*>): Type<*>? =
        reflection.structuralConnectionNodeType(type) ?: legacyCollectionNodeType(type)

    private fun isStructuralField(name: String): Boolean = name == "node" || name == "cursor" || name == "__typename"
}
