package dev.viaduct.persistence.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class ViaductPgPersistencePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = ViaductPgPersistenceExtensionDefaults.register(project)
        project.plugins.withId("com.airbnb.viaduct.module-gradle-plugin") {
            SelectiveNodeSchemaRegistrar.register(project, extension)
            PersistencePlacementRegistrar.registerModule(project, extension)
        }
        project.plugins.withId("com.airbnb.viaduct.application-gradle-plugin") {
            PersistencePlacementRegistrar.registerApplication(project)
        }
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            KotlinPersistenceProjectConfigurator(project, extension).configure()
        }
    }
}
