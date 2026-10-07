@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.reflection.GeneratedFieldReflection
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import dev.viaduct.persistence.runtime.reflection.PersistedFieldMappings
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.Connection
import viaduct.api.types.Edge
import viaduct.api.types.NodeObject

/** A reusable condition over existing storage. Each invocation supplies its own key and context. */
class DbLookup<K, N : NodeObject> private constructor(
    internal val sourceType: Type<NodeObject>,
    internal val nodeType: Type<N>,
    internal val condition: (K) -> PgGraphqlFilter,
    internal val relationship: CompositeField<*, *>? = null,
    internal val projection: CompositeField<*, *>? = null,
    internal val keyType: Type<NodeObject>? = null,
) {
    /** Returns one referenced node per matching row, preserving duplicates and source-row positions. */
    fun <T : NodeObject> project(field: CompositeField<N, T>): DbLookup<K, T> {
        require(projection == null) { "A lookup projection follows one stored to-one reference" }
        require(field.containingType.kcls == nodeType.kcls) { "Projection must belong to ${nodeType.name}" }
        require(!GeneratedFieldReflection().isListField(field.containingType, field.name)) {
            "Projection must be a to-one reference, not a collection"
        }
        requireConcrete(field.type)
        PersistedFieldMappings.requireField(field)
        return DbLookup(sourceType, field.type, condition, relationship, field, keyType)
    }

    internal fun filter(key: K): PgGraphqlFilter {
        keyType?.let { expected ->
            require(key is GlobalID<*> && key.type.kcls == expected.kcls) {
                "Lookup key must be a GlobalID for ${expected.name}"
            }
        }
        return condition(key)
    }

    companion object {
        /** Wrap an existing scalar or composite filter; the callback must not retain request state. */
        fun <K, N : NodeObject> where(
            type: Type<N>,
            condition: (K) -> PgGraphqlFilter,
        ): DbLookup<K, N> {
            requireConcrete(type)
            return DbLookup(type, type, condition)
        }

        /** Scalar fields do not expose their value type through Viaduct reflection: supply K explicitly. */
        fun <K, N : NodeObject> by(field: Field<N>): DbLookup<K, N> {
            require(field !is CompositeField<*, *>) { "Use the typed node-reference lookup for composite fields" }
            require(!GeneratedFieldReflection().isListField(field.containingType, field.name)) {
                "Equality lookup requires a scalar or to-one reference"
            }
            PersistedFieldMappings.requireField(field)
            return where(field.containingType) { key: K ->
                PgGraphqlFilter.eq(if (field.name == "id") "uuidId" else field.name, key)
            }
        }

        /** Match an existing foreign key using a typed Viaduct ID. */
        fun <N : NodeObject, T : NodeObject> by(field: CompositeField<N, T>): DbLookup<GlobalID<T>, N> {
            require(!GeneratedFieldReflection().isListField(field.containingType, field.name)) {
                "Equality lookup requires a to-one reference"
            }
            requireConcrete(field.containingType)
            requireConcrete(field.type)
            PersistedFieldMappings.requireField(field)
            return DbLookup(
                field.containingType,
                field.containingType,
                { id: GlobalID<T> -> PgGraphqlFilter.eq("${field.name}Id", id) },
                keyType = field.type,
            )
        }

        /** Follow an existing modern connection; storage direction is resolved by schema translation. */
        fun <O : NodeObject, N : NodeObject, E : Edge<N>, C : Connection<E, N>> related(
            field: CompositeField<O, C>,
        ): DbLookup<GlobalID<O>, N> {
            val shape =
                requireNotNull(GeneratedTypeReflection().connection(field.type, ownerType = field.containingType)) {
                    "Relationship must be a Viaduct connection"
                }
            @Suppress("UNCHECKED_CAST")
            return related(field, shape.nodeField.type as Type<N>)
        }

        private fun <O : NodeObject, N : NodeObject> related(
            field: CompositeField<O, *>,
            nodeType: Type<N>,
        ): DbLookup<GlobalID<O>, N> {
            requireConcrete(field.containingType)
            requireConcrete(nodeType)
            PersistedFieldMappings.requireField(field)
            return DbLookup(
                nodeType,
                nodeType,
                { id: GlobalID<O> -> PgGraphqlFilter.eq("uuidId", id) },
                relationship = field,
                keyType = field.containingType,
            )
        }

        private fun requireConcrete(type: Type<*>) {
            require(!type.kcls.java.isInterface && NodeObject::class.java.isAssignableFrom(type.kcls.java)) {
                "Lookup requires a concrete node type, not ${type.name}"
            }
            PersistedFieldMappings.requireType(type)
        }
    }
}
