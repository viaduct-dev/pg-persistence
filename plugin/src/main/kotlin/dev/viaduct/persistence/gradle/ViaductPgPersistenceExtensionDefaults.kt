package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import org.gradle.api.Project

/** Registers the extension and its defaults independently from Kotlin task wiring. */
internal object ViaductPgPersistenceExtensionDefaults {
    fun register(project: Project): ViaductPgPersistenceExtension =
        project.extensions
            .create(
                "viaductPgPersistence",
                ViaductPgPersistenceExtension::class.java,
            ).apply {
                // Matches `com.airbnb.viaduct.application-gradle-plugin`'s
                // `assembleViaductCentralSchema` output path when that task is present (today's
                // monolithic single-project consumers). A Viaduct *module* project with no
                // sibling application (e.g. a dedicated persistence tenant) never has that task,
                // so this instead falls back to the module's own local schema source directory —
                // fine because such a module owns its entire persistent Node schema and needs no
                // cross-module central assembly to generate its Hibernate model.
                centralSchemaDirectory.convention(
                    project.provider {
                        if (project.tasks.names.contains("assembleViaductCentralSchema")) {
                            project.layout.buildDirectory
                                .dir("viaduct/centralSchema")
                                .get()
                        } else {
                            project.layout.projectDirectory.dir("src/main/viaduct/schema")
                        }
                    },
                )
                implicitNamingStrategyClassName.convention(
                    ViaductImplicitNamingStrategy::class.java.name,
                )
                physicalNamingStrategyClassName.convention(
                    ViaductPhysicalNamingStrategy::class.java.name,
                )
            }
}
