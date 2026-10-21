package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.runtime.graphql.GraphqlLiteralValue
import dev.viaduct.persistence.runtime.select.PersistenceSelectionFragment
import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.SelectionSet
import graphql.language.Value
import graphql.language.VariableReference
import graphql.parser.Parser

/** Extracts direct connection arguments while preserving filters and provider-specific options. */
internal object ConnectionArgumentExtractor {
    fun fromFragment(fragment: PersistenceSelectionFragment): Map<String, ConnectionPaginationArguments> {
        val document = Parser().parseDocument(fragment.document)
        val definitions =
            document.definitions
                .filterIsInstance<FragmentDefinition>()
                .associateBy { it.name }
        val entryPoint = definitions[fragment.name] ?: return emptyMap()
        return directFields(entryPoint.selectionSet, definitions)
            .distinctBy { it.name }
            .associate { field -> field.name to fromField(field, fragment.variables) }
    }

    private fun fromField(
        field: Field,
        variables: Map<String, Any?>,
    ): ConnectionPaginationArguments =
        ConnectionPaginationArguments.fromArguments(
            field.arguments
                .asSequence()
                .map { argument ->
                    argument
                        .transform { builder ->
                            builder.value(inlineVariable(argument.value, variables))
                        }.let(AstPrinter::printAstCompact)
                }.toList(),
        )

    private fun directFields(
        selectionSet: SelectionSet,
        definitions: Map<String, FragmentDefinition>,
        visitedFragments: Set<String> = emptySet(),
    ): Sequence<Field> =
        sequence {
            for (selection in selectionSet.selections) {
                when (selection) {
                    is Field -> yield(selection)
                    is InlineFragment ->
                        yieldAll(
                            directFields(selection.selectionSet, definitions, visitedFragments),
                        )
                    is FragmentSpread ->
                        if (selection.name !in visitedFragments) {
                            definitions[selection.name]?.let { definition ->
                                yieldAll(
                                    directFields(
                                        definition.selectionSet,
                                        definitions,
                                        visitedFragments + selection.name,
                                    ),
                                )
                            }
                        }
                }
            }
        }

    private fun inlineVariable(
        value: Value<*>,
        variables: Map<String, Any?>,
    ): Value<*> =
        when (value) {
            is VariableReference -> {
                require(value.name in variables) {
                    "Selection references GraphQL variable '${value.name}' but no value was supplied"
                }
                GraphqlLiteralValue.from(variables[value.name])
            }
            is ArrayValue -> ArrayValue(value.values.map { inlineVariable(it, variables) })
            is ObjectValue ->
                ObjectValue(
                    value.objectFields.map { field ->
                        ObjectField(field.name, inlineVariable(field.value, variables))
                    },
                )
            else -> value
        }
}
