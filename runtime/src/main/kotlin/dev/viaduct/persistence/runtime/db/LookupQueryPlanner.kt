package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.connection.ConnectionQuerySource
import dev.viaduct.persistence.runtime.connection.ConnectionShape
import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Reads source rows and projects their reference IDs without changing cardinality or paging. */
internal class LookupQueryPlanner(
    private val lookup: DbLookup<*, *>,
    filter: PgGraphqlFilter,
    private val reflection: GeneratedTypeReflection,
    private val resultShape: ConnectionShape? = null,
    orderBy: List<PgGraphqlOrder> = emptyList(),
) : ConnectionQuerySource {
    private val relationship = lookup.relationship
    private val rootType = relationship?.containingType ?: lookup.sourceType
    private val entity = PgGraphqlEntity(rootType.name)
    private val sourceShape = relationship?.let { reflection.connection(it.type, ownerType = rootType) }
    private val providerType =
        if (sourceShape?.edge?.isAssociationBacked == true) {
            rootType.name + requireNotNull(relationship).name.replaceFirstChar(Char::uppercaseChar) + "Association"
        } else {
            lookup.sourceType.name
        }
    private val variables =
        buildJsonObject {
            put("lookupFilter", filter.encoded())
            if (orderBy.isNotEmpty()) put("lookupOrder", JsonArray(orderBy.map(PgGraphqlOrder::toJson)))
        }

    override fun query(
        paging: String,
        countOnly: Boolean,
    ): GraphqlQuery {
        val order = if ("lookupOrder" in variables) "orderBy: \$lookupOrder" else ""
        val arguments = arguments(paging, order, if (relationship == null) "filter: \$lookupFilter" else "")
        val page = "pageInfo { hasNextPage endCursor }"
        val edgeFields = if (relationship == null) "" else customFields()
        val rows = if (countOnly) "edges { cursor }" else "edges { cursor node { ...LookupRow } $edgeFields }"
        val rowFragment = if (countOnly) "" else rowFragment()
        val document =
            if (relationship == null) {
                val translated = translate(rowFragment, lookup.sourceType)
                "query ViaductLookup${definitions()} { " +
                    "${entity.collectionField}$arguments { $rows $page } } $translated"
            } else {
                val fragment = "fragment Main on ${rootType.name} { ${relationship.name}$arguments { $rows $page } }"
                PgGraphqlTranslation.buildRootQuery(
                    entity.collectionField,
                    "(filter: \$lookupFilter)",
                    definitions().removePrefix("(").removeSuffix(")"),
                    translate("$fragment $rowFragment", rootType),
                    singleViaFilteredCollection = true,
                )
            }
        return GraphqlQuery(document, variables, entity.collectionField)
    }

    override fun connection(response: JsonObject): JsonObject {
        val restored = PgGraphqlTranslation.restoreViaductResponseShape(response).jsonObject
        val connection =
            if (relationship == null) {
                restored
            } else {
                val owners = restored.getValue("edges").jsonArray
                if (owners.isEmpty()) return emptyConnection()
                require(owners.size == 1) { "Relationship lookup returned more than one parent" }
                owners
                    .single()
                    .jsonObject
                    .getValue("node")
                    .jsonObject
                    .getValue(relationship.name)
                    .jsonObject
            }
        val edges = JsonArray(connection.getValue("edges").jsonArray.map(::projectEdge))
        return JsonObject(connection + ("edges" to edges))
    }

    private fun rowFragment(): String {
        val projection = lookup.projection?.let { "$PROJECTED_ID: ${it.name}Id" }.orEmpty()
        val custom = if (relationship == null) customFields() else ""
        return "fragment LookupRow on ${lookup.sourceType.name} { uuidId $projection $custom }"
    }

    private fun customFields(): String =
        resultShape
            ?.edge
            ?.customFields
            .orEmpty()
            .joinToString(" ") { it.valueSelection(reflection) }

    private fun definitions(): String {
        val order = if ("lookupOrder" in variables) ", \$lookupOrder: [${providerType}OrderBy!]" else ""
        return "(\$lookupFilter: ${rootType.name}Filter!$order)"
    }

    private fun translate(
        fragment: String,
        type: viaduct.api.reflect.Type<*>,
    ): String =
        if (fragment.isBlank()) {
            ""
        } else {
            PgGraphqlTranslation.translateSelectionDocument(
                fragment,
                reflection.translationSchema(type),
                allowInternalResponseAlias = true,
            )
        }

    private fun projectEdge(value: kotlinx.serialization.json.JsonElement): JsonObject {
        val edge = value.jsonObject
        val row = edge["node"]?.takeUnless { it is JsonNull }?.jsonObject ?: return edge
        val node =
            lookup.projection?.let {
                val id = row[PROJECTED_ID]?.takeUnless { value -> value is JsonNull }
                requireNotNull(id) { "Lookup projection ${it.containingType.name}.${it.name} has no referenced ID" }
                buildJsonObject { put("uuidId", id) }
            } ?: row
        val fields =
            resultShape?.edge?.customFields.orEmpty().associate {
                it.field.name to
                    (edge[it.field.name] ?: row[it.field.name] ?: JsonNull)
            }
        return JsonObject(edge + fields + ("node" to node))
    }

    private fun emptyConnection(): JsonObject =
        buildJsonObject {
            put("edges", JsonArray(emptyList()))
            put(
                "pageInfo",
                buildJsonObject {
                    put("hasNextPage", false)
                    put("endCursor", JsonNull)
                },
            )
        }

    private fun arguments(vararg parts: String): String =
        parts
            .map { it.removePrefix("(").removeSuffix(")") }
            .filter(String::isNotBlank)
            .joinToString(", ")
            .takeIf(String::isNotEmpty)
            ?.let { "($it)" }
            .orEmpty()

    companion object {
        private const val PROJECTED_ID = "_viaduct_lookup_id"
    }
}
