package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

@CacheableTask
abstract class PreparePersistenceSchemaTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        val source = sourceDirectory.get().asFile
        val files = source.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList()
        require(files.isNotEmpty()) { "No .graphqls files found in $source" }
        val config = PersistenceConfig.load(persistenceConfigFile.files.singleOrNull())
        val schemas = files.associate { it.relativeTo(source).path to it.readText() }
        val prepared = SelectiveNodeSchema.prepare(schemas, config.deniedTypeNames)
        fileSystemOperations.sync {
            it.from(sourceDirectory)
            it.include("**/*.graphqls")
            it.into(outputDirectory)
        }
        prepared.forEach { (path, text) ->
            outputDirectory
                .file(path)
                .get()
                .asFile
                .writeText(text)
        }
    }
}
