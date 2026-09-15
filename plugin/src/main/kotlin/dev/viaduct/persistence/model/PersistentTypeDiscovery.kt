package dev.viaduct.persistence.model

import graphql.language.ObjectTypeDefinition
import graphql.parser.Parser
import viaduct.graphql.schema.ViaductSchema
import java.io.File

fun discoverPersistentTypeNames(
    schemaFiles: List<File>,
    schema: ViaductSchema,
): Set<String> =
    schemaFiles
        .sortedBy(File::getPath)
        .flatMap { schemaFile -> Parser.parse(schemaFile.readText()).definitions }
        .filterIsInstance<ObjectTypeDefinition>()
        .filter { schema.isNodeObject(it.name) }
        .mapTo(linkedSetOf()) { it.name }

private fun ViaductSchema.isNodeObject(typeName: String): Boolean {
    val typeDef = types[typeName] as? ViaductSchema.Object ?: return false
    return isNode(typeDef)
}

private fun isNode(typeDef: ViaductSchema.TypeDef): Boolean =
    (typeDef.name == "Node" && typeDef is ViaductSchema.Interface) ||
        (typeDef is ViaductSchema.OutputRecord && typeDef.supers.any { isNode(it) })
