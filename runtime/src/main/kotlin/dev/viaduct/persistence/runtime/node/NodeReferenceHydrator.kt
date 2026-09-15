@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.node
import dev.viaduct.persistence.runtime.connection.ConnectionReferenceBuilder
import dev.viaduct.persistence.runtime.db.toGRT
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Adds reference-backed fields to the object decoded from the owned db selections. */
internal class NodeReferenceHydrator(
    private val typeReflection: GeneratedTypeReflection,
) {
    private val values = NodeReferenceValueBuilder(typeReflection)

    @Suppress("UNCHECKED_CAST")
    fun <T> hydrate(
        base: JsonObject,
        selections: viaduct.api.select.SelectionSet<T>,
        references: List<NodeReferenceSelection>,
        context: ResolverExecutionContext<out Query>,
    ): T where T : CompositeOutput, T : NodeObject {
        val ownedJson =
            if (selections.isEmpty()) {
                buildJsonObject { put("__typename", selections.type.name) }
            } else {
                buildJsonObject {
                    base.forEach { (key, value) ->
                        if (key != "uuidId" && references.none { it.responseKeys.contains(key) }) {
                            put(key, value)
                        }
                    }
                }
            }
        val owned = ownedJson.toGRT(context, selections)
        return attach(owned, base, references, context)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> attach(
        base: T,
        response: JsonObject,
        references: List<NodeReferenceSelection>,
        context: ResolverExecutionContext<out Query>,
    ): T where T : CompositeOutput, T : NodeObject {
        val builder = GeneratedBuilder.fromObject(base)
        references.forEach { reference ->
            builder.set(reference.fieldName, values.build(reference, response, context))
        }
        return builder.build() as T
    }
}

private class NodeReferenceValueBuilder(
    private val typeReflection: GeneratedTypeReflection,
) {
    private val nodeResolver = NodeReferenceResolver()
    private val connectionBuilder = ConnectionReferenceBuilder(typeReflection, nodeResolver)

    fun build(
        reference: NodeReferenceSelection,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
    ): Any? =
        when (reference.kind) {
            NodeReferenceKind.ABSTRACT -> buildAbstract(reference, response, context)
            NodeReferenceKind.CONNECTION ->
                connectionBuilder.build(
                    reference,
                    requiredObject(response, reference.fieldName, "connection"),
                    context,
                )
            NodeReferenceKind.LEGACY_COLLECTION -> buildLegacyCollection(reference, response, context)
            NodeReferenceKind.TO_ONE -> buildToOne(reference, response, context)
        }

    private fun buildToOne(
        reference: NodeReferenceSelection,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
    ): NodeObject? {
        val value = response[reference.responseAlias]
        if (value == null || value is JsonNull) return null
        return nodeResolver.resolve(context, reference.nodeType, value.jsonPrimitive.content)
    }

    private fun buildAbstract(
        reference: NodeReferenceSelection,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
    ): Any? {
        val value = response[reference.fieldName]?.takeUnless { it is JsonNull } ?: return null
        val relationship = requireNotNull(reference.abstractRelationship)
        return when {
            relationship.connectionType != null -> connectionBuilder.build(reference, value.jsonObject, context)
            relationship.collection ->
                value.jsonArray.map {
                    if (it is JsonNull) null else nodeResolver.resolve(context, reference.nodeType, it.jsonObject)
                }
            else -> nodeResolver.resolve(context, reference.nodeType, value.jsonObject)
        }
    }

    private fun buildLegacyCollection(
        reference: NodeReferenceSelection,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
    ): Any {
        val collection = requiredObject(response, reference.fieldName, "collection")
        val nodes =
            collection["nodes"]?.jsonArray
                ?: error(
                    "Db response for '${reference.fieldName}' did not include 'nodes'",
                )
        val nodeValues =
            nodes.mapIndexed { index, node ->
                val nodeObject = node.jsonObject
                val internalId =
                    nodeObject["uuidId"]?.jsonPrimitive?.content
                        ?: error(
                            "Db response for '${reference.fieldName}' had an item " +
                                "at index $index with no 'uuidId'",
                        )
                nodeResolver.resolve(context, reference.nodeType, internalId)
            }
        return GeneratedBuilder
            .fromExecutionContext(typeReflection.builderClass(reference.targetType), context)
            .set("nodes", nodeValues)
            .build()
    }

    private fun requiredObject(
        response: JsonObject,
        fieldName: String,
        kind: String,
    ): JsonObject =
        response[fieldName]?.jsonObject
            ?: error(
                "Db response did not include $kind '$fieldName' while hydrating node references",
            )
}
