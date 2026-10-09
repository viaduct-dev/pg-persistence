package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles

/** All schema consumers must use the same provider validation and tracked schema/policy inputs. */
abstract class PersistenceSchemaTask : DefaultTask() {
    @get:InputDirectory
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:InputFiles
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:Input
    abstract val validatePgGraphqlFields: Property<Boolean>

    init {
        validatePgGraphqlFields.convention(true)
    }
}
