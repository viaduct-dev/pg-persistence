package dev.viaduct.persistence.pggraphql.translation

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.parser.Parser
import kotlinx.serialization.json.JsonElement

object PgGraphqlTranslation {
    private val documentTranslator = SelectionDocumentTranslator()
    private val responseShapeRestorer = ResponseShapeRestorer()

    fun translateSelectionDocument(
        document: String,
        schema: PgGraphqlTranslationSchema,
        rewriteCollectionTypes: Boolean = true,
        allowInternalResponseAlias: Boolean = false,
        concreteType: String? = null,
    ): String =
        AstPrinter.printAstCompact(
            translateSelectionDocument(
                Parser().parseDocument(document),
                schema,
                rewriteCollectionTypes,
                allowInternalResponseAlias,
                concreteType,
            ),
        )

    internal fun translateSelectionDocument(
        document: Document,
        schema: PgGraphqlTranslationSchema,
        rewriteCollectionTypes: Boolean = true,
        allowInternalResponseAlias: Boolean = false,
        concreteType: String? = null,
    ): Document {
        TranslationDocumentValidator().validateInput(
            document,
            schema,
            allowInternalResponseAlias,
        )
        val expanded = document.transform { it.definitions(listOf(SelectionFragmentExpander(document).expand())) }
        return documentTranslator.translate(
            AbstractSelectionTranslator(schema, expanded).translate(concreteType),
            schema.storedAbstractSchema(),
            rewriteCollectionTypes,
            allowInternalResponseAlias || schema.abstractTypes.relationships.isNotEmpty(),
        )
    }

    fun restoreViaductResponseShape(response: JsonElement): JsonElement = responseShapeRestorer.restore(response)

    fun restoreViaductResponsePath(path: List<JsonElement>): List<JsonElement> = responseShapeRestorer.restorePath(path)

    fun buildRootQuery(
        field: String,
        arguments: String,
        variableDefinitions: String,
        fragmentDocument: String,
        singleViaFilteredCollection: Boolean,
    ): String =
        RootQueryBuilder.build(
            field,
            arguments,
            variableDefinitions,
            fragmentDocument,
            singleViaFilteredCollection,
        )
}
