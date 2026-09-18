package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

@CacheableTask
abstract class GenerateSelectiveNodeSchemaContributionsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val source = sourceDirectory.get().asFile
        val files = source.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList()
        require(files.isNotEmpty()) { "No .graphqls files found in $source" }
        val config = PersistenceConfig.load(persistenceConfigFile.files.singleOrNull())
        val schemas = files.associate { it.relativeTo(source).path to it.readText() }
        outputFile.get().asFile.apply {
            Files.createDirectories(parentFile.toPath())
            writeText(SelectiveNodeSchema.contributions(schemas, config.deniedTypeNames))
        }
    }
}
