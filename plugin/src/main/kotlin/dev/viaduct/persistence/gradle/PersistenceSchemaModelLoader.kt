package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceModelBuilder
import dev.viaduct.persistence.model.discoverPersistentTypeNames
import dev.viaduct.persistence.model.validatePgGraphqlDbs
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import viaduct.graphql.utils.DefaultSchemaFactory
import java.io.File

/** Rebuilds the semantic model from the same declarative inputs used by source generation. */
internal object PersistenceSchemaModelLoader {
    fun build(
        centralSchemaDirectory: File,
        persistenceConfigFile: File?,
        validateSelectiveResolvers: Boolean = false,
    ): PersistenceModel {
        val schemaFiles = schemaFiles(centralSchemaDirectory)
        val registry = TypeDefinitionRegistry()
        schemaFiles.forEach { registry.merge(SchemaParser().parse(it)) }
        // Module-local schemas rely on Viaduct's built-in Node, scalars, and directives.
        DefaultSchemaFactory.addDefaults(registry, allowExisting = true)
        val schema = ViaductSchemaFactory.fromTypeDefinitionRegistry(registry)
        val config = PersistenceConfig.load(persistenceConfigFile)
        val discoveredTypeNames = discoverPersistentTypeNames(schemaFiles, schema)
        val invalidDeniedTypes = config.deniedTypeNames - discoveredTypeNames
        require(invalidDeniedTypes.isEmpty()) {
            "${persistenceConfigFile?.path}: denyList.types contains types that are not eligible " +
                "persistent GraphQL objects: ${invalidDeniedTypes.sorted().joinToString()}"
        }
        val persistentTypeNames = discoveredTypeNames - config.deniedTypeNames
        if (validateSelectiveResolvers) validateSelectiveNodeResolvers(schema, persistentTypeNames)
        validatePgGraphqlDbs(schema, persistentTypeNames)
        return PersistenceModelBuilder().build(
            schema = schema,
            selectedTypeNames = persistentTypeNames,
            policy = config,
        )
    }

    fun schemaFiles(directory: File): List<File> =
        directory
            .walkTopDown()
            .filter { it.isFile && it.extension == "graphqls" }
            .sortedBy { it.relativeTo(directory).path }
            .toList()
            .also {
                require(it.isNotEmpty()) {
                    "No assembled Viaduct schema files found in $directory"
                }
            }
}
