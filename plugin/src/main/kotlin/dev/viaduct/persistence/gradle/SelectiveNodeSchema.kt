package dev.viaduct.persistence.gradle

import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.Document
import graphql.language.ImplementingTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.TypeName
import graphql.parser.Parser

/** Normalizes a generated copy, including existing @resolver arguments, without duplicate directives. */
internal object SelectiveNodeSchema {
    fun prepare(
        schemas: Map<String, String>,
        deniedTypeNames: Set<String>,
    ): Map<String, String> {
        val documents = schemas.mapValues { Parser.parse(it.value) }
        val definitions = documents.values.flatMap { it.definitions }.filterIsInstance<ImplementingTypeDefinition<*>>()
        val nodeTypes = nodeTypes(definitions)
        val resolverOwners =
            definitions
                .filterIsInstance<ObjectTypeDefinition>()
                .groupBy { it.name }
                .filterKeys { it in nodeTypes && it !in deniedTypeNames }
                .filterValues { parts -> parts.any { it !is ObjectTypeExtensionDefinition } }
                .mapValues { (name, parts) ->
                    val declared = parts.filter { it.hasDirective("resolver") }
                    require(declared.size <= 1) { "$name declares @resolver in multiple schema definitions" }
                    declared.singleOrNull() ?: parts.first { it !is ObjectTypeExtensionDefinition }
                }
        return documents.mapValues { (path, document) ->
            val updated =
                document.definitions.map { definition ->
                    if (definition is ObjectTypeDefinition && resolverOwners[definition.name] === definition) {
                        val directives = selectiveDirectives(definition, path)
                        if (definition is ObjectTypeExtensionDefinition) {
                            definition.transformExtension { it.directives(directives) }
                        } else {
                            definition.transform { it.directives(directives) }
                        }
                    } else {
                        definition
                    }
                }
            AstPrinter.printAst(Document.newDocument().definitions(updated).build()) + "\n"
        }
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

    private fun selectiveDirectives(
        definition: ObjectTypeDefinition,
        path: String,
    ): List<Directive> {
        val resolvers = definition.directives.filter { it.name == "resolver" }
        require(resolvers.size <= 1) { "$path: ${definition.name} declares @resolver more than once" }
        val resolver = resolvers.singleOrNull() ?: Directive.newDirective().name("resolver").build()
        val selective = resolver.arguments.filter { it.name == "isSelective" || it.name == "selective" }
        require(selective.all { (it.value as? BooleanValue)?.isValue == true }) {
            "$path: ${definition.name} is a database node but explicitly disables selective resolution. " +
                "Remove the false argument or exclude the type with denyList.types."
        }
        val arguments = resolver.arguments.filterNot { it.name == "isSelective" || it.name == "selective" }
        val enabled =
            resolver.transform {
                it.arguments(
                    arguments +
                        Argument
                            .newArgument()
                            .name("isSelective")
                            .value(BooleanValue(true))
                            .build(),
                )
            }
        return definition.directives.filterNot { it.name == "resolver" } + enabled
    }
}
