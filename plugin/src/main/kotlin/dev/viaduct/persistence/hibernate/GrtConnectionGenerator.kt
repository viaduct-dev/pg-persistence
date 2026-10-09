package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry

/** Uses typed generated builders; there is no reflective connection planner in the delegate path. */
internal class GrtConnectionGenerator {
    fun sources(
        model: PersistenceModel,
        registry: TypeDefinitionRegistry,
        grtPackage: String,
    ): Map<String, String> =
        registry
            .types()
            .values
            .filterIsInstance<ObjectTypeDefinition>()
            .filter { type -> type.directives.any { it.name == "connection" } }
            .mapNotNull { connection ->
                val fields = schemaFields(registry, connection.name)
                val edges = requireNotNull(fields.singleOrNull { it.name == "edges" })
                val edgeName = edges.type.baseName()
                val edgeFields = schemaFields(registry, edgeName)
                val nodeName = requireNotNull(edgeFields.singleOrNull { it.name == "node" }).type.baseName()
                if (model.entities.none { it.graphqlName == nodeName }) return@mapNotNull null
                require(edgeFields.any { it.name == "cursor" }) {
                    "Connection ${connection.name} requires modern Viaduct cursors"
                }
                val nodes = if (fields.any { it.name == "nodes" }) "        builder.nodes(nodes)" else ""
                "${connection.name}Binding.kt" to
                    """@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package $grtPackage.persistence

import $grtPackage.${connection.name}
import $grtPackage.$edgeName
import dev.viaduct.persistence.orm.grt.GrtConnectionBinding
import viaduct.api.types.OffsetCursor

object ${connection.name}Binding {
    val binding = GrtConnectionBinding(
        type = ${connection.name}.Reflection,
        node = ${nodeName}Entity.BINDING,
        build = { context, nodes, offset, next, previous ->
            val edges = nodes.mapIndexed { index, node ->
                $edgeName.Builder(context).node(node)
                    .cursor(OffsetCursor.fromOffset(Math.addExact(offset, index)).value).build()
            }
            val builder = ${connection.name}.Builder(context)
            builder.fromEdges(edges, next, previous)
$nodes
            builder.build()
        },
    )
}
"""
            }.toMap()
}
