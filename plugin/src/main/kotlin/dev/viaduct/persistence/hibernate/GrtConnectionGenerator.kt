package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry

/** Concrete edge builders retain persisted edge fields and concrete nodes behind abstract types. */
internal class GrtConnectionGenerator {
    fun sources(
        model: PersistenceModel,
        registry: TypeDefinitionRegistry,
        grtPackage: String,
    ): Map<String, String> {
        val shapes = delegateShapes(model, registry)
        return registry
            .types()
            .values
            .filterIsInstance<ObjectTypeDefinition>()
            .filter { type -> type.directives.any { it.name == "connection" } }
            .mapNotNull { connection -> source(connection.name, model, registry, shapes, grtPackage) }
            .toMap()
    }

    private fun source(
        name: String,
        model: PersistenceModel,
        registry: TypeDefinitionRegistry,
        shapes: List<GrtDelegateShape>,
        grtPackage: String,
    ): Pair<String, String>? {
        val fields = schemaFields(registry, name)
        val edgeName = requireNotNull(fields.singleOrNull { it.name == "edges" }).type.baseName()
        val edgeFields = schemaFields(registry, edgeName)
        val nodeName = requireNotNull(edgeFields.singleOrNull { it.name == "node" }).type.baseName()
        val possibleNodes =
            shapes.filter {
                it.grtName?.let { grt -> model.abstractTypes.accepts(nodeName, grt) } == true
            }
        if (possibleNodes.isEmpty()) return null
        require(edgeFields.any { it.name == "cursor" }) { "Connection $name requires modern Viaduct cursors" }
        val cases =
            shapes
                .filter { it.grtName == edgeName }
                .map {
                    "            is ${it.className} -> row.project(context, session, " +
                        "selections.selectionSetFor($name.Fields.edges)).toBuilder().cursor(cursor.value).build()"
                }.toMutableList()
        val storedEdge = hasStoredFields(edgeFields)
        if (!storedEdge) cases += nodeCases(name, edgeName, possibleNodes)
        val nodes =
            if (fields.any { it.name == "nodes" }) {
                "            builder.nodes(edges.map { requireNotNull(it.getNode()) })"
            } else {
                ""
            }
        return "${name}Binding.kt" to """@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package $grtPackage.persistence

import $grtPackage.*
import dev.viaduct.persistence.orm.grt.*

object ${name}Binding {
    val binding = GrtConnectionBinding<$name, $edgeName>(
        type = $name.Reflection,
        edge = { context, session, row, cursor, selections -> when (row) {
${cases.joinToString("\n")}
            else -> error("Query rows do not match $name; use its mapped edge rows or concrete node entities")
        } },
        read = { context, session, entityName, selections -> when (entityName) {
${readCases(
            name,
            edgeName,
            fields.any { it.name == "nodes" },
            shapes.filter { it.grtName == edgeName },
            if (storedEdge) emptyList() else possibleNodes,
        ).joinToString("\n")}
            else -> error("Row binding does not match $name")
        } },
        build = { context, edges, next, previous ->
            val builder = $name.Builder(context)
            builder.fromEdges(edges, next, previous)
$nodes
            builder.build()
        },
    )
}
"""
    }

    private fun hasStoredFields(fields: List<graphql.language.FieldDefinition>): Boolean =
        fields.any {
            it.name !in setOf("node", "cursor") && it.directives.none { directive -> directive.name == "resolver" }
        }

    private fun readCases(
        connection: String,
        edge: String,
        hasNodes: Boolean,
        edges: List<GrtDelegateShape>,
        nodes: List<GrtDelegateShape>,
    ): List<String> {
        val nodeSelections = "selections.selectionSetFor($connection.Fields.edges).selectionSetFor($edge.Fields.node)"
        val children =
            if (hasNodes) {
                "mergeSelections($nodeSelections, selections.selectionSetFor($connection.Fields.nodes))"
            } else {
                nodeSelections
            }
        val edgeCases =
            edges.map { shape ->
                """            "${shape.name}" -> {
                val binding = ${shape.className}.BINDING
                val reader = requireNotNull(binding.reader)
                val selected = withNodeSelections(selections.selectionSetFor($connection.Fields.edges), $edge.Fields.node, $children)
                val fields = binding.fields.filter { selected.contains(it) }.map { it.name }.toSet()
                GrtConnectionRead(reader, fields) { row, cursor ->
                    reader.value(context, session, row, fields, selected).toBuilder().cursor(cursor.value).build()
                }
            }"""
            }
        return edgeCases +
            nodes.map { shape ->
                val node =
                    if (shape.node) {
                        "context.ref(context.globalIDFor(${shape.className}.BINDING.type, row.identity.toString()))"
                    } else {
                        "reader.value(context, session, row, fields, selected)"
                    }
                val fields =
                    if (shape.node) {
                        "emptySet<String>()"
                    } else {
                        "binding.fields.filter { selected.contains(it) }.map { it.name }.toSet()"
                    }
                """            "${shape.name}" -> {
                val binding = ${shape.className}.BINDING
                val reader = requireNotNull(binding.reader)
                val selected = ($children).selectionSetFor(${shape.grtName}.Reflection)
                val fields = $fields
                GrtConnectionRead(reader, fields) { row, cursor ->
                    $edge.Builder(context).node($node).cursor(cursor.value).build()
                }
            }"""
            }
    }

    private fun nodeCases(
        connection: String,
        edge: String,
        nodes: List<GrtDelegateShape>,
    ): List<String> {
        val cases =
            nodes.map { shape ->
                val node =
                    if (shape.node) {
                        "context.ref(context.globalIDFor(${shape.className}.BINDING.type, " +
                            "requireNotNull(row.internalId).toString()))"
                    } else {
                        "row.project(context, session, selections.selectionSetFor($connection.Fields.edges)" +
                            ".selectionSetFor($edge.Fields.node).selectionSetFor(${shape.grtName}.Reflection))"
                    }
                "            is ${shape.className} -> $edge.Builder(context).node($node).cursor(cursor.value).build()"
            }
        return cases +
            if (nodes.size == 1 && nodes.single().node) {
                val shape = nodes.single()
                listOf(
                    "            is java.util.UUID -> $edge.Builder(context)" +
                        ".node(context.ref(context.globalIDFor(${shape.className}.BINDING.type, row.toString())))" +
                        ".cursor(cursor.value).build()",
                )
            } else {
                emptyList()
            }
    }
}
