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
    @Suppress("LongParameterList") // Placement inputs complement the existing schema/validation inputs.
    fun build(
        centralSchemaDirectory: File,
        persistenceConfigFile: File?,
        validateSelectiveResolvers: Boolean = false,
        schemaContributions: Collection<File> = emptyList(),
        modulePlacementArtifacts: Collection<File> = emptyList(),
        localModuleIdentity: String? = null,
    ): PersistenceModel {
        val schemaFiles = schemaFiles(centralSchemaDirectory) + schemaContributions.sorted()
        val registry = TypeDefinitionRegistry()
        schemaFiles.forEach { registry.merge(SchemaParser().parse(it)) }
        // Module-local schemas rely on Viaduct's built-in Node, scalars, and directives.
        DefaultSchemaFactory.addDefaults(registry, allowExisting = true)
        val schema = ViaductSchemaFactory.fromTypeDefinitionRegistry(registry)
        val config = PersistenceConfig.load(persistenceConfigFile)
        val placements = modulePlacementArtifacts + centralSchemaDirectory.resolve(".persistence")
        val policy = PersistenceModulePolicies.merge(config, placements)
        val discoveredTypeNames = discoverPersistentTypeNames(schemaFiles, schema)
        val invalidDeniedTypes = policy.deniedTypeNames - discoveredTypeNames
        require(invalidDeniedTypes.isEmpty()) {
            "${persistenceConfigFile?.path}: excluded type policies contain types that are not eligible " +
                "persistent GraphQL objects: ${invalidDeniedTypes.sorted().joinToString()}"
        }
        val persistentTypeNames = discoveredTypeNames - policy.deniedTypeNames
        if (validateSelectiveResolvers) {
            val localDefinitions = PersistencePlacements.ownedTypes(modulePlacementArtifacts, localModuleIdentity)
            validateSelectiveNodeResolvers(
                schema,
                localDefinitions?.let { persistentTypeNames intersect it } ?: persistentTypeNames,
            )
        }
        validatePgGraphqlDbs(schema, persistentTypeNames)
        val model =
            PersistenceModelBuilder()
                .build(
                    schema = schema,
                    selectedTypeNames = persistentTypeNames,
                    policy = policy,
                )
        return PersistencePlacements.apply(
            model,
            schemaFiles,
            placements,
            config.databaseSchema,
            localModuleIdentity,
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
