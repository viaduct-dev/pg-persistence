package dev.viaduct.persistence.hibernate

import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataBuilder
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.ImplicitNamingStrategy
import org.hibernate.boot.model.naming.PhysicalNamingStrategy
import org.hibernate.boot.registry.StandardServiceRegistry
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader

fun interface HibernateMetadataCustomizer {
    fun customize(metadataBuilder: MetadataBuilder)
}

class HibernateMetadataHandle internal constructor(
    val metadata: Metadata,
    private val registry: StandardServiceRegistry,
    private val classLoader: URLClassLoader,
    private val previousContextClassLoader: ClassLoader?,
) : Closeable {
    override fun close() {
        Thread.currentThread().contextClassLoader = previousContextClassLoader
        StandardServiceRegistryBuilder.destroy(registry)
        classLoader.close()
    }
}

object HibernateMetadataBootstrap {
    /** Include private library tables as well as application schemas in Liquibase operations. */
    fun schemaNames(configuration: HibernateMetadataConfiguration): String =
        build(configuration).use { handle ->
            handle.metadata
                .collectTableMappings()
                .filter { it.isPhysicalTable }
                .map { it.schemaOrPublic() }
                .distinct()
                .sorted()
                .joinToString(",")
        }

    fun build(configuration: HibernateMetadataConfiguration): HibernateMetadataHandle {
        configuration.validate()
        val previousContextClassLoader = Thread.currentThread().contextClassLoader
        val classLoader = createClassLoader(configuration)
        Thread.currentThread().contextClassLoader = classLoader

        var registry: StandardServiceRegistry? = null
        var handleCreated = false
        try {
            val currentRegistry = createRegistry(configuration, classLoader)
            registry = currentRegistry
            return buildHandle(
                configuration,
                classLoader,
                currentRegistry,
                previousContextClassLoader,
            ).also { handleCreated = true }
        } finally {
            if (!handleCreated) {
                registry?.let(StandardServiceRegistryBuilder::destroy)
                Thread.currentThread().contextClassLoader = previousContextClassLoader
                classLoader.close()
            }
        }
    }

    private fun createClassLoader(configuration: HibernateMetadataConfiguration): URLClassLoader =
        URLClassLoader(
            configuration.classpath
                .map(File::toURI)
                .map { it.toURL() }
                .toTypedArray(),
            HibernateMetadataBootstrap::class.java.classLoader,
        )

    private fun createRegistry(
        configuration: HibernateMetadataConfiguration,
        classLoader: URLClassLoader,
    ): StandardServiceRegistry =
        StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", configuration.dialectClassName)
            .applySettings(configuration.hibernateSettings)
            .applySetting("hibernate.classLoaders", listOf(classLoader))
            .build()

    private fun buildHandle(
        configuration: HibernateMetadataConfiguration,
        classLoader: URLClassLoader,
        registry: StandardServiceRegistry,
        previousContextClassLoader: ClassLoader?,
    ): HibernateMetadataHandle {
        val metadata = buildMetadata(configuration, classLoader, registry)
        validateManagedEntities(configuration, metadata)
        return HibernateMetadataHandle(
            metadata = metadata,
            registry = registry,
            classLoader = classLoader,
            previousContextClassLoader = previousContextClassLoader,
        )
    }

    private fun buildMetadata(
        configuration: HibernateMetadataConfiguration,
        classLoader: URLClassLoader,
        registry: StandardServiceRegistry,
    ): Metadata {
        val metadataBuilder =
            MetadataSources(registry)
                .addFile(configuration.mappingFile)
                .metadataBuilder
                .applyTempClassLoader(classLoader)
        metadataBuilder.applyImplicitNamingStrategy(
            classLoader.instantiate(
                configuration.implicitNamingStrategyClassName,
                ImplicitNamingStrategy::class.java,
            ),
        )
        metadataBuilder.applyPhysicalNamingStrategy(
            classLoader.instantiate(
                configuration.physicalNamingStrategyClassName,
                PhysicalNamingStrategy::class.java,
            ),
        )
        configuration.metadataCustomizerClassNames.forEach { className ->
            classLoader
                .instantiate(className, HibernateMetadataCustomizer::class.java)
                .customize(metadataBuilder)
        }
        return metadataBuilder.build()
    }

    private fun validateManagedEntities(
        configuration: HibernateMetadataConfiguration,
        metadata: Metadata,
    ) {
        val actualManagedEntities = metadata.entityBindings.map { it.entityName }.toSet()
        val expectedManagedEntities = configuration.managedEntityNames.toSet()
        require(actualManagedEntities == expectedManagedEntities) {
            "Hibernate managed entities differ from the metadata configuration: " +
                "missing=${(expectedManagedEntities - actualManagedEntities).sorted()}, " +
                "unexpected=${(actualManagedEntities - expectedManagedEntities).sorted()}"
        }
    }
}

private fun <T> ClassLoader.instantiate(
    className: String,
    expectedType: Class<T>,
): T {
    val instance = loadClass(className).getDeclaredConstructor().newInstance()
    require(expectedType.isInstance(instance)) {
        "$className must implement ${expectedType.name}"
    }
    return expectedType.cast(instance)
}
