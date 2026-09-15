package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

internal class PersistenceModelValidator {
    fun validateTargetForeignKeyFields(context: PersistenceModelContext) {
        context.unidirectionalTargetForeignKeyFields.forEach { coordinate ->
            validateTargetForeignKeyField(coordinate, context)
        }
    }

    fun validateNoConflictingScalarRelationshipIds(
        source: ViaductSchema.Object,
        relationships: Map<out ViaductSchema.Field, PersistenceRelationshipTarget?>,
    ) {
        val shadowedRelationships =
            relationships
                .filterValues { it != null && !it.collection && !it.idOfDirected }
                .filter { (field, relationship) ->
                    val scalarField = source.fields.singleOrNull { it.name == "${field.name}Id" }
                    val scalarRelationship = scalarField?.let(relationships::get)
                    scalarField != null &&
                        (
                            scalarRelationship?.idOfDirected != true ||
                                scalarRelationship.targetName != relationship?.targetName
                        )
                }.keys
        require(shadowedRelationships.isEmpty()) {
            "Persistent type ${source.name} represents the same relationship as both an object " +
                "and a scalar ID: " +
                shadowedRelationships.joinToString { "${it.name}/${it.name}Id" } +
                ". Keep the object relationship in GraphQL and remove its scalar ID shadow."
        }
    }

    private fun validateTargetForeignKeyField(
        coordinate: String,
        context: PersistenceModelContext,
    ) {
        val (typeName, fieldName) = parseCoordinate(coordinate)
        val source =
            context.includedObjects[typeName]
                ?: error("Target-foreign-key field '$coordinate' has no persistent source type")
        val field =
            source.fields.singleOrNull { it.name == fieldName }
                ?: error("Target-foreign-key field '$coordinate' does not exist")
        require(context.relationships(source).getValue(field)?.collection == true) {
            "Target-foreign-key field '$coordinate' must be a persistent collection"
        }
    }

    private fun parseCoordinate(coordinate: String): Pair<String, String> {
        val parts = coordinate.split('.', limit = 2)
        require(parts.size == 2) {
            "Target-foreign-key field '$coordinate' must use the form Type.field"
        }
        return parts[0] to parts[1]
    }
}
