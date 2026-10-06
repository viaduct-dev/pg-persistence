package dev.viaduct.persistence.gradle

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.ImplementingTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.TypeName
import graphql.parser.Parser

/** Adds defaults only for implemented node resolvers, preserving explicit schema declarations. */
internal object SelectiveNodeSchema {
    fun contributions(
        schemas: Map<String, String>,
        resolvers: Map<String, Boolean>,
    ): String {
        val definitions =
            schemas.values
                .flatMap { Parser.parse(it).definitions }
                .filterIsInstance<ImplementingTypeDefinition<*>>()
        val nodes = mutableSetOf("Node")
        do {
            val added =
                definitions
                    .filter { type -> type.implements.any { (it as TypeName).name in nodes } }
                    .map { it.name }
                    .let(nodes::addAll)
        } while (added)
        val types = definitions.filterIsInstance<ObjectTypeDefinition>().groupBy { it.name }
        val extensions =
            resolvers.toSortedMap().mapNotNull { (name, batching) ->
                val parts = types[name].orEmpty()
                require(name in nodes && parts.any { it !is ObjectTypeExtensionDefinition }) {
                    "@Resolver for $name does not refer to a Node object defined in this module's schema"
                }
                if (parts.any { it.hasDirective("resolver") }) {
                    null
                } else {
                    Parser
                        .parse("extend type $name @resolver(isSelective: true, isBatching: $batching)")
                        .definitions
                        .single() as ObjectTypeExtensionDefinition
                }
            }
        if (extensions.isEmpty()) return ""
        return AstPrinter.printAst(Document.newDocument().definitions(extensions).build()) + "\n"
    }
}
