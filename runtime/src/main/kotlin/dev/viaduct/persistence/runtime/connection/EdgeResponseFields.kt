@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.db.toGRT
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import graphql.language.AstPrinter
import graphql.language.FragmentDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** The conventional edge node is a Viaduct node reference backed by pg_graphql.uuidId. */
internal class NodeResponseField(
    override val field: CompositeField<*, *>,
) : EdgeResponseField {
    override fun selection(path: ConnectionPath): String = "${field.name} { ${path.nodeSelection()} }"

    fun resolve(
        edge: JsonObject,
        path: ConnectionPath,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    ): NodeObject? =
        path.targetNode(edge)?.let { node ->
            resolveNode(node, context, nodeResolver)
        }

    fun resolveNode(
        node: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    ): NodeObject? {
        val internalId =
            node["uuidId"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content
        return internalId?.let { nodeResolver.resolve(context, field.type, node) }
    }

    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
        path: ConnectionPath,
    ) {
        builder.set(field, resolve(response, path, context, nodeResolver))
    }
}

internal class CursorResponseField(
    override val field: Field<*>,
) : EdgeResponseField {
    override fun selection(path: ConnectionPath): String = field.name

    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
        path: ConnectionPath,
    ) {
        builder.set(
            field,
            response[field.name]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content,
        )
    }
}

/** A scalar, enum, list, or custom scalar edge field. */
internal class JsonEdgeResponseField(
    override val field: Field<*>,
) : StoredEdgeResponseField {
    override fun valueSelection(typeReflection: GeneratedTypeReflection?): String = field.name

    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
        path: ConnectionPath,
    ) {
        builder.setJson(field, response.edgeValue(field.name, path))
    }
}

/** A custom edge relationship whose target is a Node object. */
internal class NodeEdgeResponseField(
    override val field: CompositeField<*, *>,
) : StoredEdgeResponseField {
    override fun valueSelection(typeReflection: GeneratedTypeReflection?): String = "${field.name} { uuidId }"

    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
        path: ConnectionPath,
    ) {
        val internalId =
            response
                .edgeValue(field.name, path)
                ?.takeUnless { it is JsonNull }
                ?.jsonObject
                ?.get("uuidId")
                ?.jsonPrimitive
                ?.content
        builder.set(field, internalId?.let { nodeResolver.resolve(context, field.type, it) })
    }
}

/** A persisted association represented as an ordinary (non-Node) composite object. */
internal class CompositeJsonEdgeResponseField(
    override val field: CompositeField<*, *>,
    private val selections: SelectionSet<*>,
) : StoredEdgeResponseField {
    override fun valueSelection(typeReflection: GeneratedTypeReflection?): String =
        "${field.name} { ${selections.selectionText(typeReflection)} }"

    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
        path: ConnectionPath,
    ) {
        val value = response.edgeValue(field.name, path)?.takeUnless { it is JsonNull }
        if (value == null) {
            builder.set(field, null)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val typedSelections = selections as SelectionSet<CompositeOutput>
        val decoded =
            when (value) {
                is JsonArray -> value.map { it.jsonObject.toGRT(context, typedSelections) }
                else -> value.jsonObject.toGRT(context, typedSelections)
            }
        builder.set(field, decoded)
    }
}

internal fun customEdgeResponseField(
    field: Field<*>,
    selections: SelectionSet<*>?,
): StoredEdgeResponseField =
    when {
        field is CompositeField<*, *> && NodeObject::class.java.isAssignableFrom(field.type.kcls.java) ->
            NodeEdgeResponseField(field)
        field is CompositeField<*, *> &&
            CompositeOutput::class.java.isAssignableFrom(field.type.kcls.java) ->
            CompositeJsonEdgeResponseField(
                field,
                checkNotNull(selections) {
                    "Custom connection edge field '${field.name}' requires a selection set"
                },
            )
        else -> JsonEdgeResponseField(field)
    }

private fun SelectionSet<*>.selectionText(typeReflection: GeneratedTypeReflection?): String {
    val document = Parser().parseDocument(toFragment().document)
    val translated =
        if (typeReflection == null) {
            document
        } else {
            PgGraphqlTranslation.translateSelectionDocument(
                document,
                typeReflection.translationSchema(type),
                allowInternalResponseAlias = true,
            )
        }
    val definition = translated.definitions.filterIsInstance<FragmentDefinition>().single()
    return definition.selectionSet.selections.joinToString(" ") {
        AstPrinter.printAstCompact(it)
    }
}

private fun ConnectionPath.nodeSelection(): String = if (isAssociationBacked) "node { uuidId }" else "uuidId"

private fun JsonObject.edgeValue(
    fieldName: String,
    path: ConnectionPath,
) = path.edgeValue(this, fieldName)
