package dev.viaduct.persistence.pggraphql.translation

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.Selection
import graphql.language.TypeName

/** Coordinates concrete fragment projection and stored relationship selection rewriting. */
internal class AbstractSelectionTranslator(
    private val schema: PgGraphqlTranslationSchema,
    private val document: Document,
) {
    private val mappings = schema.abstractTypes
    private val fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
    private val projector = ConcreteSelectionProjector(mappings, ::projectField)

    fun translate(concreteType: String? = null): Document {
        if (mappings.possibleTypes.isEmpty() && concreteType == null) return document
        val main = requireNotNull(fragments["Main"] ?: fragments.values.firstOrNull()) { "Missing selection fragment" }
        val declared = requireNotNull(main.typeCondition.name)
        val concrete = concreteType ?: declared
        require(concrete !in mappings.possibleTypes) {
            "Read root $declared requires a concrete pg_graphql table type. Pass DbRead.concreteType explicitly."
        }
        require(mappings.accepts(declared, concrete)) { "$concrete is not a possible type of $declared" }
        val selections = projector.project(main.selectionSet, concrete)
        val result =
            main.transform {
                it.typeCondition(TypeName(concrete))
                it.selectionSet(if (declared != concrete) selections.withTypename() else selections)
            }
        return document.transform { it.definitions(listOf(result)) }
    }

    private fun projectField(
        field: Field,
        parent: String,
    ): Selection<*> {
        require(field.alias?.startsWith(ABSTRACT_ALIAS_PREFIX) != true) { "Reserved persistence alias ${field.alias}" }
        val selections = field.selectionSet ?: return field
        val relationship = mappings.relationship(parent, field.name)
        val childType = schema.fieldType(parent, field.name)
        return when {
            relationship != null -> AbstractRelationshipSelection(relationship, projector).translate(field)
            childType == null || childType in mappings.possibleTypes -> field
            else -> field.transform { it.selectionSet(projector.project(selections, childType)) }
        }
    }
}
