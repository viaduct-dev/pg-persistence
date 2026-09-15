package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

/** Builds the persisted portion of an edge object; node and cursor remain connection metadata. */
internal class PersistenceEdgeMappingFactory {
    private val fieldFactory = PersistenceFieldAttributeFactory(generatedGlobalId = false)

    fun build(
        edgeType: ViaductSchema.Object,
        modelContext: PersistenceModelContext,
    ): PersistenceEdgeMapping? {
        val attributes =
            edgeType.fields
                .filterNot { isStructuralField(it.name) }
                .flatMap { field ->
                    require(modelContext.relationships(edgeType)[field]?.isAbstract != true) {
                        "Abstract relationship ${edgeType.name}.${field.name} on a custom edge is not supported"
                    }
                    fieldFactory.build(edgeType, field, modelContext)
                }
        return attributes.takeIf { it.isNotEmpty() }?.let {
            PersistenceEdgeMapping(edgeType.name, it)
        }
    }

    private fun isStructuralField(name: String): Boolean = name == "node" || name == "cursor" || name == "__typename"
}
