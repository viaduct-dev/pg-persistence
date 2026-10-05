package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

internal class PersistenceModelValidator {
    fun validateRelationships(
        schema: ViaductSchema,
        context: PersistenceModelContext,
        deniedTypeNames: Set<String>,
    ) {
        val associationNames = schema.types.keys.toMutableSet()
        context.includedObjects.values.forEach { owner ->
            context.relationships(owner).forEach { (field, relationship) ->
                if (relationship == null) return@forEach
                require(relationship.targets.isNotEmpty()) {
                    "Stored field ${relationship.coordinate} has no concrete targets"
                }
                relationship.targets.forEach { name ->
                    require(name !in deniedTypeNames) {
                        "Persisted field '${relationship.coordinate}' targets denied type '$name'"
                    }
                    val target = context.includedObjects[name]
                    require(target != null && (!relationship.isAbstract || isNode(target))) {
                        "Stored field ${relationship.coordinate} has non-persistent concrete target $name"
                    }
                }
                if (relationship.isAbstract) {
                    require(field.type.listDepth <= 1) {
                        "Nested lists are not supported for ${relationship.coordinate}"
                    }
                    validateGeneratedNames(owner, relationship, associationNames)
                }
            }
        }
    }

    private fun validateGeneratedNames(
        owner: ViaductSchema.Object,
        relationship: PersistenceRelationship,
        associationNames: MutableSet<String>,
    ) {
        if (relationship.collection) {
            require(associationNames.add(relationship.associationType)) {
                "Generated association type ${relationship.associationType} collides at ${relationship.coordinate}"
            }
        } else {
            val generated = relationship.targetAttributes().flatMap { listOf(it.name, "${it.name}Id") }
            val collisions = generated.intersect(owner.fields.map { it.name }.toSet())
            require(collisions.isEmpty()) {
                "Generated names for ${relationship.coordinate} collide with $collisions"
            }
        }
    }

    fun validateTargetForeignKeyFields(context: PersistenceModelContext) {
        context.unidirectionalTargetForeignKeyFields.forEach { coordinate ->
            validateTargetForeignKeyField(coordinate, context)
        }
    }

    fun validateNoConflictingScalarRelationshipIds(
        source: ViaductSchema.Object,
        relationships: Map<out ViaductSchema.Field, PersistenceRelationship?>,
    ) {
        val shadowedRelationships =
            relationships
                .filterValues { it != null && !it.isAbstract && !it.collection && !it.idOfDirected }
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
