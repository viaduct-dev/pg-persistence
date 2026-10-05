package dev.viaduct.persistence.runtime.reflection

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlFieldCoordinate
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import dev.viaduct.persistence.runtime.connection.ConnectionStorageClassifier
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput

/** Builds immutable translator metadata from generated Viaduct types. */
internal class GeneratedTranslationSchemaFactory(
    private val reflection: GeneratedTypeReflection,
    private val fieldReflection: GeneratedFieldReflection,
    private val storageClassifier: ConnectionStorageClassifier =
        ConnectionStorageClassifier(fieldReflection),
) {
    fun build(rootType: Type<*>): PgGraphqlTranslationSchema {
        val abstractTypes = AbstractTypeMappings.load(rootType.kcls.java.classLoader)
        val visited = mutableSetOf<String>()
        val schema = SchemaAccumulator()
        val pending = ArrayDeque<Type<*>>().apply { add(rootType) }

        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!visited.add(type.name)) continue
            abstractTypes.possibleTypes[type.name].orEmpty().forEach {
                pending +=
                    reflection.reflectedType(
                        rootType,
                        it,
                    )
            }
            schema.include(type, pending)
        }
        return PgGraphqlTranslationSchema(
            schema.collections,
            schema.fieldTypes,
            schema.associationConnections,
            abstractTypes,
        )
    }

    private inner class SchemaAccumulator {
        val collections = linkedMapOf<String, String>()
        val fieldTypes = linkedMapOf<PgGraphqlFieldCoordinate, String>()
        val associationConnections = linkedSetOf<PgGraphqlFieldCoordinate>()

        fun include(
            type: Type<*>,
            pending: ArrayDeque<Type<*>>,
        ) {
            reflection.legacyCollectionNodeType(type)?.let { collections[type.name] = it.name }
            fieldReflection.allFields(type).forEach { field ->
                val composite = field as? CompositeField<*, *> ?: return@forEach
                val coordinate = PgGraphqlFieldCoordinate(type.name, field.name)
                fieldTypes[coordinate] = composite.type.name
                if (storageClassifier.isAssociationBacked(type, composite.type)) associationConnections += coordinate
                if (CompositeOutput::class.java.isAssignableFrom(composite.type.kcls.java)) pending += composite.type
            }
        }
    }
}
