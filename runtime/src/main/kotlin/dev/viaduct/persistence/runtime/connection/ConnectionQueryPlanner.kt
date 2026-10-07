package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.db.DbRead
import dev.viaduct.persistence.runtime.db.DbResponseReader
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlOrder
import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.node.NodeReferenceKind
import dev.viaduct.persistence.runtime.node.NodeReferenceSelection
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import graphql.language.OperationDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import viaduct.api.reflect.CompositeField

/** The only native cursors accepted here are produced by the private count traversal. */
internal class ConnectionQueryPlanner(
    private val read: DbRead,
    private val shape: ConnectionShape,
    private val field: CompositeField<*, *>?,
    private val reflection: GeneratedTypeReflection,
    filter: PgGraphqlFilter,
    orderBy: List<PgGraphqlOrder>,
) : ConnectionQuerySource {
    private val options =
        buildMap {
            if (filter.encoded().isNotEmpty()) put("viaductConnectionFilter", filter.encoded())
            if (orderBy.isNotEmpty()) {
                put("viaductConnectionOrderBy", JsonArray(orderBy.map(PgGraphqlOrder::toJson)))
            }
        }
    private val relationship =
        field?.let {
            AbstractTypeMappings
                .load(it.containingType.kcls.java.classLoader)
                .relationship(it.containingType.name, it.name)
        }
    private val providerType =
        relationship?.rowType ?: if (field != null && shape.edge.isAssociationBacked) {
            field.containingType.name + field.name.replaceFirstChar(Char::uppercaseChar) + "Association"
        } else {
            shape.nodeField.type.name
        }

    init {
        val definitions =
            read.root.variableDefinitions
                .takeIf(String::isNotBlank)
                ?.let {
                    Parser()
                        .parseDocument("query($it) { __typename }")
                        .getFirstDefinitionOfType(OperationDefinition::class.java)
                        .orElseThrow()
                        .variableDefinitions
                        .map { it.name }
                }.orEmpty()
        require((read.root.variables.keys + definitions).none { it in options }) {
            "Root variables cannot replace connection filter or ordering variables"
        }
    }

    override fun query(
        paging: String,
        countOnly: Boolean,
    ): GraphqlQuery {
        val optionArguments =
            buildList {
                if ("viaductConnectionFilter" in options) add("filter: \$viaductConnectionFilter")
                if ("viaductConnectionOrderBy" in options) add("orderBy: \$viaductConnectionOrderBy")
            }.joinToString(", ")
        val arguments = mergeArguments(paging, optionArguments)
        val definitions =
            buildList {
                add(read.root.variableDefinitions)
                if ("viaductConnectionFilter" in options) add("\$viaductConnectionFilter: ${providerType}Filter")
                if ("viaductConnectionOrderBy" in options) add("\$viaductConnectionOrderBy: [${providerType}OrderBy!]")
            }.filter(String::isNotBlank).joinToString(", ")
        val selection = if (countOnly) countSelection(arguments) else pageSelection(arguments)
        val type = field?.containingType ?: shape.type
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on ${type.name} { $selection }",
                reflection.translationSchema(type),
                allowInternalResponseAlias = true,
            )
        val root = read.root
        val rootArguments = if (field == null) mergeArguments(root.arguments, arguments) else root.arguments
        return GraphqlQuery(
            PgGraphqlTranslation.buildRootQuery(
                root.field,
                rootArguments,
                definitions,
                translated,
                root.singleViaFilteredCollection,
            ),
            JsonObject(root.variables + options),
            root.responseKey,
        )
    }

    override fun connection(response: JsonObject): JsonObject {
        val restored = PgGraphqlTranslation.restoreViaductResponseShape(response).jsonObject
        if (field == null) return restored
        val parent = DbResponseReader.firstNode(restored, read.root.responseKey)
        val key = if (field.name in parent) field.name else shape.path(field.name).requestFieldName
        return parent.getValue(key).jsonObject
    }

    private fun countSelection(arguments: String): String {
        val selection = "edges { cursor } pageInfo { hasNextPage endCursor }"
        if (field == null) return selection
        val name = if (relationship != null) field.name else shape.path(field.name).requestFieldName
        return "$name$arguments { $selection }"
    }

    private fun pageSelection(arguments: String): String {
        if (field == null) {
            val path = ConnectionPath(read.root.field)
            val fields = shape.fields().mapNotNull { it.selection(path, reflection) }.joinToString(" ")
            return "$fields pageInfo { hasNextPage endCursor }"
        }
        return NodeReferenceSelection(
            fieldName = field.name,
            targetType = shape.type,
            kind = if (relationship == null) NodeReferenceKind.CONNECTION else NodeReferenceKind.ABSTRACT,
            nodeType = shape.nodeField.type,
            connection = shape,
            connectionArguments = ConnectionPaginationArguments.backend(arguments),
            abstractRelationship = relationship,
        ).upstreamSelection(reflection).removeSuffix("}") + " pageInfo { hasNextPage endCursor } }"
    }
}

private fun mergeArguments(vararg groups: String): String =
    groups
        .map { it.trim().removePrefix("(").removeSuffix(")") }
        .filter(String::isNotBlank)
        .joinToString(", ")
        .takeIf(String::isNotBlank)
        ?.let { "($it)" }
        .orEmpty()
