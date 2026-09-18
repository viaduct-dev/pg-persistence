package dev.viaduct.persistence.gradle

import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.Document
import graphql.language.ImplementingTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.TypeName
import graphql.parser.Parser

/** Builds additive Viaduct schema extensions for persistent node resolver defaults. */
internal object SelectiveNodeSchema {
    fun contributions(
        schemas: Map<String, String>,
        deniedTypeNames: Set<String>,
    ): String {
        val documents = schemas.mapValues { Parser.parse(it.value) }
        val definitions = documents.values.flatMap { it.definitions }.filterIsInstance<ImplementingTypeDefinition<*>>()
        val nodeTypes = nodeTypes(definitions)
        val sourceByDefinition =
            documents.flatMap { (path, document) -> document.definitions.map { it to path } }.toMap()
        val extensions =
            definitions
                .filterIsInstance<ObjectTypeDefinition>()
                .groupBy { it.name }
                .filterKeys { it in nodeTypes && it !in deniedTypeNames }
                .filterValues { parts -> parts.any { it !is ObjectTypeExtensionDefinition } }
                .toSortedMap()
                .mapNotNull { (name, parts) ->
                    val declared = parts.filter { it.hasDirective("resolver") }
                    require(declared.size <= 1) { "$name declares @resolver in multiple schema definitions" }
                    val resolver = declared.singleOrNull()
                    if (resolver != null) {
                        requireSelectiveResolver(resolver, sourceByDefinition.getValue(resolver))
                        null
                    } else {
                        ObjectTypeExtensionDefinition
                            .newObjectTypeExtensionDefinition()
                            .name(name)
                            .directives(listOf(selectiveResolver()))
                            .build()
                    }
                }
        if (extensions.isEmpty()) return ""
        return AstPrinter.printAst(Document.newDocument().definitions(extensions).build()) + "\n"
    }

    private fun nodeTypes(definitions: List<ImplementingTypeDefinition<*>>): Set<String> {
        val nodes = mutableSetOf("Node")
        do {
            val added =
                definitions
                    .filter { type -> type.implements.any { (it as TypeName).name in nodes } }
                    .map { it.name }
                    .let(nodes::addAll)
        } while (added)
        return nodes
    }

    private fun requireSelectiveResolver(
        definition: ObjectTypeDefinition,
        path: String,
    ) {
        val resolver = definition.directives.single { it.name == "resolver" }
        val selective = resolver.arguments.filter { it.name == "isSelective" || it.name == "selective" }
        require(selective.any { (it.value as? BooleanValue)?.isValue == true }) {
            "$path: ${definition.name} is a database node with an existing @resolver that is not selective. " +
                "Set isSelective: true or exclude the type with denyList.types."
        }
    }

    private fun selectiveResolver(): Directive =
        Parser
            .parse("extend type Node @resolver(isSelective: true)")
            .definitions
            .filterIsInstance<ObjectTypeExtensionDefinition>()
            .single()
            .directives
            .single()
}
