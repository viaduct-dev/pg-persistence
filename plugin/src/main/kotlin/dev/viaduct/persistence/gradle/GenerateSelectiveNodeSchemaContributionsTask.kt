package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

@CacheableTask
abstract class GenerateSelectiveNodeSchemaContributionsTask
    @Inject
    constructor(
        private val workerExecutor: WorkerExecutor,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaFiles: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val resolverSources: ConfigurableFileCollection

        @get:Classpath
        abstract val parserClasspath: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun generate() {
            val schemas = schemaFiles.files.sorted().associate { it.path to it.readText() }
            val sources = resolverSources.files.sorted().associate { it.path to it.readText() }
            workerExecutor
                .classLoaderIsolation { it.classpath.from(parserClasspath) }
                .submit(GenerateSelectiveNodeSchemaContributionsWork::class.java) {
                    it.schemas.set(schemas)
                    it.sources.set(sources)
                    it.outputDirectory.set(outputDirectory)
                }
        }
    }
