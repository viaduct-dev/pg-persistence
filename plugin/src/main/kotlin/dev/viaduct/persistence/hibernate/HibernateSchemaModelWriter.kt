package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.io.ensureDirectory
import dev.viaduct.persistence.model.PersistenceModel
import java.io.File

/** Inputs used when generating the dynamic Hibernate mapping artifacts. */
data class HibernateSchemaModelWriteRequest(
    val model: PersistenceModel,
    val outputDirectory: File,
    val options: HibernateSchemaModelWriteOptions = HibernateSchemaModelWriteOptions(),
)

/** Optional names and replacement mapping used by [HibernateSchemaModelWriteRequest]. */
data class HibernateSchemaModelWriteOptions(
    val persistenceUnitName: String = HibernateSchemaModelWriter.DEFAULT_PERSISTENCE_UNIT,
    val replacementHbmXml: File? = null,
)

class HibernateSchemaModelWriter {
    private val persistenceWriter = PersistenceXmlWriter()
    private val hbmWriter = HbmXmlWriter()

    fun write(request: HibernateSchemaModelWriteRequest) {
        val model = request.model
        val outputDirectory = request.outputDirectory
        val options = request.options
        outputDirectory.deleteRecursively()
        val resourcesDirectory = outputDirectory.resolve("resources/META-INF")
        resourcesDirectory.ensureDirectory()
        HibernateXmlDocuments.write(
            persistenceWriter.document(options.persistenceUnitName),
            resourcesDirectory.resolve("persistence.xml"),
        )
        resourcesDirectory.resolve("viaduct-persistence-semantic-not-null.txt").writeText(
            model.semanticNotNullCoordinates.sorted().joinToString(separator = "\n", postfix = "\n"),
        )
        val mappingDestination = resourcesDirectory.resolve("viaduct-persistence.hbm.xml")
        if (options.replacementHbmXml == null) {
            HibernateXmlDocuments.write(
                hbmWriter.document(model),
                mappingDestination,
            )
        } else {
            options.replacementHbmXml.copyTo(mappingDestination)
        }
    }

    /** Compatibility overload for callers that pass generation options individually. */
    @Suppress("LongParameterList")
    fun write(
        model: PersistenceModel,
        outputDirectory: File,
        persistenceUnitName: String = DEFAULT_PERSISTENCE_UNIT,
        replacementHbmXml: File? = null,
    ) = write(
        HibernateSchemaModelWriteRequest(
            model = model,
            outputDirectory = outputDirectory,
            options =
                HibernateSchemaModelWriteOptions(
                    persistenceUnitName = persistenceUnitName,
                    replacementHbmXml = replacementHbmXml,
                ),
        ),
    )

    companion object {
        const val DEFAULT_PERSISTENCE_UNIT = "gateloom-schema"
    }
}
