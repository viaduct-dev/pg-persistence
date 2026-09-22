package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

abstract class ValidatePgGraphqlDbsTask : DefaultTask() {
    @get:InputDirectory
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:InputFiles
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @TaskAction
    fun validate() {
        PersistenceSchemaModelLoader.build(
            centralSchemaDirectory = centralSchemaDirectory.get().asFile,
            persistenceConfigFile = persistenceConfigFile.files.singleOrNull(),
        )
    }
}
