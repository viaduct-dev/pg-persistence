package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.parser.Parser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal class SemanticNotNullValidator(
    private val coordinates: Set<String>,
) {
    private val parsedDocuments = ConcurrentHashMap<String, graphql.language.Document>()

    fun validate(request: SemanticValidationRequest): List<UpstreamGraphqlError> {
        val violations = mutableListOf<UpstreamGraphqlError>()
        if (coordinates.isNotEmpty()) validateInto(request, violations)
        return request.errors + violations
    }

    private fun validateInto(
        request: SemanticValidationRequest,
        violations: MutableList<UpstreamGraphqlError>,
    ) {
        val parsed = parsedDocuments.computeIfAbsent(request.document, Parser()::parseDocument)
        val fragments = parsed.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        val main = fragments["Main"]
        if (main != null) {
            val normalizedErrorPaths = request.errors.map { it.path.drop(1) }
            visit(
                selectionSet = main.selectionSet,
                parentType = request.rootType,
                values = listOf(ValueAtPath(request.data, emptyList())),
                fragments = fragments,
                schema = request.schema,
                normalizedErrorPaths = normalizedErrorPaths,
                rootResponseKey = request.rootResponseKey,
                violations = violations,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun visit(
        selectionSet: SelectionSet,
        parentType: String,
        values: List<ValueAtPath>,
        fragments: Map<String, FragmentDefinition>,
        schema: PgGraphqlTranslationSchema,
        normalizedErrorPaths: List<List<JsonElement>>,
        rootResponseKey: String,
        violations: MutableList<UpstreamGraphqlError>,
    ) {
        selectionSet.selections.forEach { selection ->
            when (selection) {
                is Field ->
                    visitField(
                        selection,
                        parentType,
                        values,
                        fragments,
                        schema,
                        normalizedErrorPaths,
                        rootResponseKey,
                        violations,
                    )
                is FragmentSpread ->
                    fragments[selection.name]?.let {
                        visit(
                            it.selectionSet,
                            it.typeCondition?.name ?: parentType,
                            values,
                            fragments,
                            schema,
                            normalizedErrorPaths,
                            rootResponseKey,
                            violations,
                        )
                    }
                is InlineFragment ->
                    visit(
                        selection.selectionSet,
                        selection.typeCondition?.name ?: parentType,
                        values,
                        fragments,
                        schema,
                        normalizedErrorPaths,
                        rootResponseKey,
                        violations,
                    )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun visitField(
        field: Field,
        parentType: String,
        parents: List<ValueAtPath>,
        fragments: Map<String, FragmentDefinition>,
        schema: PgGraphqlTranslationSchema,
        normalizedErrorPaths: List<List<JsonElement>>,
        rootResponseKey: String,
        violations: MutableList<UpstreamGraphqlError>,
    ) {
        val responseKey = field.alias ?: field.name
        val coordinate = "$parentType.${field.name}"
        val children =
            parents.flatMap { parent ->
                val value = (parent.value as? JsonObject)?.get(responseKey)
                val path = parent.path + JsonPrimitive(responseKey)
                if (coordinate in coordinates && isNull(value) && normalizedErrorPaths.none { path.explainedBy(it) }) {
                    violations +=
                        UpstreamGraphqlError(
                            message = "Semantic non-null field '$coordinate' returned null without an error",
                            path = listOf(JsonPrimitive(rootResponseKey)) + path,
                            extensions = JsonObject(mapOf("code" to JsonPrimitive("SEMANTIC_NON_NULL_VIOLATION"))),
                        )
                }
                expand(value, path)
            }
        val childType = schema.fieldType(parentType, field.name)
        val childSelections = field.selectionSet
        if (childSelections != null && childType != null && children.isNotEmpty()) {
            visit(
                childSelections,
                childType,
                children,
                fragments,
                schema,
                normalizedErrorPaths,
                rootResponseKey,
                violations,
            )
        }
    }

    private fun expand(
        value: JsonElement?,
        path: List<JsonElement>,
    ): List<ValueAtPath> =
        when (value) {
            is JsonArray -> value.mapIndexed { index, element -> ValueAtPath(element, path + JsonPrimitive(index)) }
            is JsonObject -> listOf(ValueAtPath(value, path))
            else -> emptyList()
        }

    private fun isNull(value: JsonElement?): Boolean = value == null || value is JsonNull

    private fun List<JsonElement>.explainedBy(errorPath: List<JsonElement>): Boolean =
        size <= errorPath.size && indices.all { this[it] == errorPath[it] }

    private data class ValueAtPath(
        val value: JsonElement,
        val path: List<JsonElement>,
    )
}

internal data class SemanticValidationRequest(
    val data: JsonObject,
    val errors: List<UpstreamGraphqlError>,
    val document: String,
    val rootType: String,
    val rootResponseKey: String,
    val schema: PgGraphqlTranslationSchema,
)

internal object SemanticNotNullCoordinates {
    private const val RESOURCE = "META-INF/viaduct-persistence-semantic-not-null.txt"

    private val cache = WeakHashMap<ClassLoader, Set<String>>()

    fun load(classLoader: ClassLoader): Set<String> =
        synchronized(cache) {
            cache.getOrPut(classLoader) { loadUncached(classLoader) }
        }

    private fun loadUncached(classLoader: ClassLoader): Set<String> =
        classLoader.getResources(RESOURCE).toList().let { resources ->
            val policies =
                resources.map { resource ->
                    resource
                        .readText()
                        .lineSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toSet()
                }
            check(policies.distinct().size <= 1) {
                "Multiple Viaduct persistence semantic-nullability policies are visible to one DbClient; " +
                    "use a classloader scoped to one persistence module"
            }
            policies.firstOrNull().orEmpty()
        }
}
