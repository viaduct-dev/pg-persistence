package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

@CacheableTask
abstract class GenerateSelectiveNodeSchemaContributionsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = schemaFiles.singleFile
        val files = source.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList()
        require(files.isNotEmpty()) { "The Viaduct module schema partition contains no .graphqls files" }
        val config = PersistenceConfig.load(persistenceConfigFile.files.singleOrNull())
        val schemas = files.associate { it.relativeTo(source).path to it.readText() }
        val directory = outputDirectory.get().asFile
        Files.createDirectories(directory.toPath())
        val output = directory.resolve("pg-persistence.graphqls")
        val contribution = SelectiveNodeSchema.contributions(schemas, config.deniedTypeNames)
        if (contribution.isBlank()) {
            Files.deleteIfExists(output.toPath())
        } else {
            output.writeText(contribution)
        }
    }
}
