package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.GrtDelegateGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Opt-in source generation; does not participate in Liquibase/schema-only task dependencies. */
abstract class GenerateGrtDelegatesTask : DefaultTask() {
    @get:InputDirectory
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val grtPackage: Property<String>

    @get:InputFiles
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        val pkg = grtPackage.orNull
        if (pkg == null) {
            // Remove stale delegates if an application disables the option on a subsequent build.
            output.deleteRecursively()
            return
        }
        val schema = centralSchemaDirectory.get().asFile
        val model =
            PersistenceSchemaModelLoader.build(
                schema,
                persistenceConfigFile.files.singleOrNull(),
                validatePgGraphqlFields = false,
            )
        GrtDelegateGenerator().write(model, pkg, PersistenceSchemaModelLoader.schemaFiles(schema), output)
    }
}
