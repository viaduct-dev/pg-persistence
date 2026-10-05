package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.parser.Parser
import viaduct.api.select.SelectionSet
import graphql.language.SelectionSet as GraphqlSelectionSet

/** Converts a Viaduct selection into one executable pg_graphql operation. */
internal class DbQueryPlanner(
    private val typeReflection: GeneratedTypeReflection,
) {
    fun plan(
        root: DbRoot,
        selections: SelectionSet<*>,
        referenceSelections: List<String> = emptyList(),
        concreteType: viaduct.api.reflect.Type<out viaduct.api.types.CompositeOutput>? = null,
    ): GraphqlQuery {
        val document = selections.toFragment().document
        val documentWithReferences =
            if (referenceSelections.isEmpty()) {
                document
            } else {
                addReferenceFragment(document, selections.type.name, referenceSelections)
            }
        val schema = typeReflection.translationSchema(selections.type)
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                documentWithReferences,
                schema,
                allowInternalResponseAlias = true,
                concreteType = concreteType?.name,
            )
        return GraphqlQuery(
            text =
                PgGraphqlTranslation.buildRootQuery(
                    field = root.field,
                    arguments = root.arguments,
                    variableDefinitions = root.variableDefinitions,
                    fragmentDocument = translated,
                    singleViaFilteredCollection = root.singleViaFilteredCollection,
                ),
            variables = root.variables,
            responseKey = root.responseKey,
        )
    }

    private fun addReferenceFragment(
        document: String,
        typeName: String,
        selections: List<String>,
    ): String {
        val parsed = Parser().parseDocument(document)
        val spread = FragmentSpread.newFragmentSpread(REFERENCE_FRAGMENT).build()
        val definitions =
            parsed.definitions
                .map { definition ->
                    if (definition is FragmentDefinition && definition.name == "Main") {
                        definition.transform { builder ->
                            builder.selectionSet(
                                GraphqlSelectionSet
                                    .newSelectionSet(
                                        definition.selectionSet.selections + spread,
                                    ).build(),
                            )
                        }
                    } else {
                        definition
                    }
                }.toMutableList()
        val referenceFragment =
            Parser()
                .parseDocument(
                    """
                    fragment $REFERENCE_FRAGMENT on $typeName {
                      ${selections.joinToString("\n")}
                    }
                    """.trimIndent(),
                ).getFirstDefinitionOfType(FragmentDefinition::class.java)
                .orElseThrow()
        definitions += referenceFragment
        return AstPrinter.printAstCompact(
            Document.newDocument().definitions(definitions).build(),
        )
    }

    private companion object {
        const val REFERENCE_FRAGMENT = "ViaductNodeReferences"
    }
}
