package dev.viaduct.persistence.pggraphql.translation

import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.TypeName

/** Translates authored Viaduct fragments into pg_graphql-compatible fragments. */
internal class SelectionDocumentTranslator(
    private val selectionTransformer: SelectionTransformerChain = SelectionTransformerChain(),
    private val validator: TranslationDocumentValidator = TranslationDocumentValidator(),
) {
    fun translate(
        document: Document,
        schema: PgGraphqlTranslationSchema,
        rewriteCollectionTypes: Boolean,
        allowInternalResponseAlias: Boolean,
    ): Document {
        validator.validateInput(document, schema, allowInternalResponseAlias)
        val definitions =
            document.definitions.map { definition ->
                if (definition !is FragmentDefinition) return@map definition
                val sourceType = requireNotNull(definition.typeCondition.name)
                val selections =
                    selectionTransformer.transform(
                        definition.selectionSet,
                        sourceType,
                        schema,
                        rewriteCollectionTypes,
                    )
                definition.transform { builder ->
                    builder.selectionSet(selections)
                    schema.associationRowType(sourceType)?.let { associationType ->
                        builder.typeCondition(TypeName(associationType))
                    }
                    if (rewriteCollectionTypes) {
                        schema.collectionNodeType(sourceType)?.let { elementType ->
                            builder.typeCondition(TypeName("${elementType}Connection"))
                        }
                    }
                }
            }
        val translated = document.transform { it.definitions(definitions) }
        validator.validateTranslation(document, translated, schema)
        return translated
    }
}
