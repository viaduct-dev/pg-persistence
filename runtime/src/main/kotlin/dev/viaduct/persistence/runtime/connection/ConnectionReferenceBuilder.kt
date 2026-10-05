@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.node.NodeReferenceSelection
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.types.OffsetCursor
import viaduct.api.types.Query

/** Builds generated Viaduct connection, edge, and page-info objects from pg_graphql JSON. */
internal class ConnectionReferenceBuilder(
    private val typeReflection: GeneratedTypeReflection,
    private val nodeResolver: NodeReferenceResolver,
) {
    fun build(
        reference: NodeReferenceSelection,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
    ): Any {
        val shape =
            checkNotNull(reference.connection) {
                "Connection reference '${reference.fieldName}' has no reflected connection shape"
            }
        val connectionBuilder =
            GeneratedBuilder.fromExecutionContext(
                typeReflection.builderClass(shape.type),
                context,
            )

        val path = shape.path(reference.fieldName)
        val valueContext =
            ConnectionFieldValueContext(
                executionContext = context,
                typeReflection = typeReflection,
                nodeResolver = nodeResolver,
                connectionFieldName = reference.fieldName,
                path = path,
            )
        // Unpaged structural wrappers may still be read as lists, but never return a provider cursor.
        val normalized =
            response[shape.edgeField.name]?.jsonArray?.let { edges ->
                JsonObject(
                    response + (
                        shape.edgeField.name to
                            JsonArray(
                                edges.mapIndexed { index, edge ->
                                    val cursor = OffsetCursor.fromOffset(index).value
                                    JsonObject(edge.jsonObject + ("cursor" to JsonPrimitive(cursor)))
                                },
                            )
                    ),
                )
            } ?: response
        shape.fields().forEach { field ->
            connectionBuilder.set(
                field.field,
                field.value(
                    response = normalized,
                    context = valueContext,
                ),
            )
        }
        return connectionBuilder.build()
    }
}
