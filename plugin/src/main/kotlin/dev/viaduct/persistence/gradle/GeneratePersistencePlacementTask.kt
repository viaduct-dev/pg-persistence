package dev.viaduct.persistence.gradle

import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Ownership travels with schema artifacts, without a dependency on resolver/GRT compilation. */
@CacheableTask
abstract class GeneratePersistencePlacementTask : DefaultTask() {
    @get:Input
    abstract val moduleIdentity: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val config = PersistenceConfig.load(persistenceConfigFile.files.singleOrNull())
        val definitions =
            schemaFiles.files.sorted().flatMap { file ->
                Parser()
                    .parseDocument(file.readText())
                    .definitions
                    .filterIsInstance<ObjectTypeDefinition>()
                    .filterNot { it is ObjectTypeExtensionDefinition }
                    .map { it.name }
            }
        require(definitions.distinct().size == definitions.size) {
            "Ambiguous type ownership in ${moduleIdentity.get()}"
        }
        val document =
            buildJsonObject {
                put("version", 1)
                put("module", moduleIdentity.get())
                put("databaseSchema", config.databaseSchema ?: "public")
                put("types", JsonArray(definitions.sorted().map(::JsonPrimitive)))
                put(
                    "policyYaml",
                    persistenceConfigFile.files
                        .singleOrNull()
                        ?.takeIf { it.isFile }
                        ?.readText()
                        .orEmpty(),
                )
            }
        val file = outputFile.get().asFile
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        file.writeText(document.toString())
    }
}
