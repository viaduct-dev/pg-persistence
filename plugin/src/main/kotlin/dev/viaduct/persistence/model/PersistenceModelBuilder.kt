package dev.viaduct.persistence.model

import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import viaduct.graphql.schema.ViaductSchema

data class PersistenceModelPolicy(
    val deniedTypeNames: Set<String> = emptySet(),
    val semanticNotNullTypeNames: Set<String> = emptySet(),
    val semanticNotNullFieldCoordinates: Set<String> = emptySet(),
    val unidirectionalTargetForeignKeyFields: Set<String> = emptySet(),
    val inverseFieldOverrides: Map<String, String> = emptyMap(),
)

class PersistenceModelBuilder {
    private val modelValidator = PersistenceModelValidator()
    private val entityAttributeFactory = PersistenceEntityAttributeFactory(modelValidator)

    fun build(
        schema: ViaductSchema,
        selectedTypeNames: Set<String>,
        policy: PersistenceModelPolicy = PersistenceModelPolicy(),
    ): PersistenceModel {
        val includedObjects = resolveIncludedObjects(schema, selectedTypeNames)
        val modelContext =
            PersistenceModelContext(
                includedObjects = includedObjects,
                schemaTypes = schema.types,
                policy = policy,
            )
        modelValidator.validateRelationships(schema, modelContext, policy.deniedTypeNames)
        modelContext.validateSemanticNotNullCoordinates()
        modelValidator.validateTargetForeignKeyFields(modelContext)
        val entities =
            includedObjects.values
                .sortedBy { it.name }
                .map { type ->
                    entityAttributeFactory.build(
                        type = type,
                        generatedGlobalId = isNode(type),
                        modelContext = modelContext,
                    )
                }

        return PersistenceModel(
            entities = entities + modelContext.generatedEntities.values,
            enums = modelContext.generatedEnums.values.sortedBy { it.graphqlName },
            semanticNotNullCoordinates = modelContext.semanticNotNullCoordinates(),
            abstractTypes =
                AbstractTypeMappings(
                    possibleTypes =
                        schema.types.values
                            .filter { it.isAbstract }
                            .associate { type ->
                                type.name to type.possibleObjectTypes.mapTo(sortedSetOf()) { it.name }
                            },
                    relationships =
                        includedObjects.values.flatMap { type ->
                            modelContext.relationships(type).values.mapNotNull { it?.abstractMapping() }
                        },
                ),
        )
    }

    private fun resolveIncludedObjects(
        schema: ViaductSchema,
        selectedTypeNames: Set<String>,
    ): Map<String, ViaductSchema.Object> =
        selectedTypeNames.associateWith { typeName ->
            schema.types[typeName] as? ViaductSchema.Object
                ?: error("Persistence type '$typeName' is not a GraphQL object")
        }
}
