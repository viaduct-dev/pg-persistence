@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.types.Query

internal interface ConnectionResponseField {
    val field: Field<*>

    fun selection(path: ConnectionPath): String?

    fun selection(
        path: ConnectionPath,
        typeReflection: GeneratedTypeReflection,
    ): String? = selection(path)

    fun value(
        response: JsonObject,
        context: ConnectionFieldValueContext,
    ): Any?
}

internal data class ConnectionFieldValueContext(
    val executionContext: ResolverExecutionContext<out Query>,
    val typeReflection: GeneratedTypeReflection,
    val nodeResolver: NodeReferenceResolver,
    val connectionFieldName: String,
    val path: ConnectionPath,
)

/** Restores a compatibility `nodes` selection from the pg_graphql edge response. */
internal class NodesResponseField(
    override val field: CompositeField<*, *>,
    private val edgeField: CompositeField<*, *>,
    private val edge: EdgeShape,
) : ConnectionResponseField {
    override fun selection(path: ConnectionPath): String = "${edgeField.name} { ${edge.node.selection(path)} }"

    override fun value(
        response: JsonObject,
        context: ConnectionFieldValueContext,
    ): Any =
        response["nodes"]
            ?.jsonArray
            ?.mapIndexed { index, nodeJson ->
                edge.node.resolveNode(
                    nodeJson.jsonObject,
                    context.executionContext,
                    context.nodeResolver,
                ) ?: error(
                    "Db response for '${context.connectionFieldName}' had a null node at index $index",
                )
            }
            ?: response[edgeField.name]
                ?.jsonArray
                ?.mapIndexed { index, edgeJson ->
                    edge.node.resolve(
                        edgeJson.jsonObject,
                        context.path,
                        context.executionContext,
                        context.nodeResolver,
                    ) ?: error(
                        "Db response for '${context.connectionFieldName}' had a null node at index $index",
                    )
                }
            ?: error(
                "Db response for '${context.connectionFieldName}' did not include " +
                    "'nodes' or '${edgeField.name}'",
            )
}

internal class EdgesResponseField(
    override val field: Field<*>,
    private val edge: EdgeShape,
) : ConnectionResponseField {
    override fun selection(path: ConnectionPath): String {
        val edgeSelections = edge.fields.joinToString(" ") { it.selection(path) }
        return "${field.name} { $edgeSelections }"
    }

    override fun selection(
        path: ConnectionPath,
        typeReflection: GeneratedTypeReflection,
    ): String {
        val edgeSelections = edge.fields.joinToString(" ") { it.selection(path, typeReflection) }
        return "${field.name} { $edgeSelections }"
    }

    override fun value(
        response: JsonObject,
        context: ConnectionFieldValueContext,
    ): Any =
        response[field.name]
            ?.jsonArray
            ?.mapIndexed { index, edgeJson ->
                edge.build(
                    edgeJson.jsonObject,
                    EdgeBuildContext(
                        index = index,
                        connectionTypeName = context.connectionFieldName,
                        executionContext = context.executionContext,
                        typeReflection = context.typeReflection,
                        nodeResolver = context.nodeResolver,
                        path = context.path,
                    ),
                )
            }
            ?: error("Db response for '${context.connectionFieldName}' did not include '${field.name}'")
}

internal class PageInfoResponseField(
    override val field: Field<*>,
    private val shape: PageInfoShape,
) : ConnectionResponseField {
    override fun selection(path: ConnectionPath): String? = shape.selection()?.let { "${field.name} { $it }" }

    override fun value(
        response: JsonObject,
        context: ConnectionFieldValueContext,
    ): Any =
        shape.build(
            response[field.name]?.jsonObject
                ?: error(
                    "Db response for '${context.connectionFieldName}' did not include '${field.name}'",
                ),
            context.executionContext,
            context.typeReflection,
            context.nodeResolver,
        )
}
