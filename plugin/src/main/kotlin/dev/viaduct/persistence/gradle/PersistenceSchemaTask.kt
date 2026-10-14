package dev.viaduct.persistence.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional

/** Schema consumers share tracked schema, policy, and module ownership inputs. */
abstract class PersistenceSchemaTask : DefaultTask() {
    @get:InputDirectory
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:InputFiles
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:InputFiles
    abstract val modulePlacementArtifacts: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val localModuleIdentity: Property<String>
}
