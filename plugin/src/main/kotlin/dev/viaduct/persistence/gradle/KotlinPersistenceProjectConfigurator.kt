package dev.viaduct.persistence.gradle

import org.gradle.api.Project

/** Coordinates the independent build registrations applied to a Kotlin JVM project. */
internal class KotlinPersistenceProjectConfigurator(
    private val project: Project,
    private val extension: ViaductPgPersistenceExtension,
) {
    fun configure() {
        val layout = PersistenceBuildLayout(project)
        PersistenceGenerationRegistrar(project, extension, layout).register()
        val effective = EffectiveModelRegistrar(project, extension, layout).register()
        HibernateSnapshotTaskRegistrar(project, extension, layout).register(effective)
        HibernateDiffTaskRegistrar(project, extension, layout).register(effective)
        project.tasks.withType(PersistenceSchemaTask::class.java).configureEach {
            it.validatePgGraphqlFields.set(extension.delegateGrtPackage.map { false }.orElse(true))
        }
    }
}
