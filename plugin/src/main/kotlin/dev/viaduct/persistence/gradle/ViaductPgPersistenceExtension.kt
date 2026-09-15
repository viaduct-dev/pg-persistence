package dev.viaduct.persistence.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class ViaductPgPersistenceExtension {
    abstract val centralSchemaDirectory: DirectoryProperty
    abstract val replacementHbmXml: RegularFileProperty
    abstract val implicitNamingStrategyClassName: Property<String>
    abstract val physicalNamingStrategyClassName: Property<String>
    abstract val metadataCustomizerClassNames: ListProperty<String>

    /** Optional schema-adjacent YAML policy; defaults to `src/main/viaduct/persistence.yaml`. */
    abstract val persistenceConfigFile: RegularFileProperty
    abstract val schemaDiffUrl: Property<String>
    abstract val schemaDiffUser: Property<String>
    abstract val schemaDiffPassword: Property<String>

    init {
        metadataCustomizerClassNames.convention(emptyList())
        schemaDiffUrl.convention("jdbc:postgresql://127.0.0.1:54322/postgres")
        schemaDiffUser.convention("postgres")
        schemaDiffPassword.convention("postgres")
    }
}
