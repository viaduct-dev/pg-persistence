package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import java.io.File

/**
 * Builds Hibernate metadata inputs from the semantic persistence model.
 *
 * Only [mappingFile], [classpath], and [semanticModel] are mandatory; the
 * remaining fields are policy knobs that default to Viaduct's standard configuration.
 */
@Suppress("LongParameterList")
class HibernateMetadataConfigurationInput(
    val mappingFile: File,
    classpath: List<File>,
    val semanticModel: PersistenceModel,
    val implicitNamingStrategyClassName: String = ViaductImplicitNamingStrategy::class.java.name,
    val physicalNamingStrategyClassName: String = ViaductPhysicalNamingStrategy::class.java.name,
    metadataCustomizerClassNames: List<String> = emptyList(),
    val dialectClassName: String = HibernateMetadataConfiguration.DEFAULT_DIALECT,
    hibernateSettings: Map<String, String> = HibernateMetadataConfiguration.defaultSettings(),
) {
    val classpath: List<File> = java.util.List.copyOf(classpath)
    val metadataCustomizerClassNames: List<String> =
        java.util.List.copyOf(metadataCustomizerClassNames)
    val hibernateSettings: Map<String, String> =
        java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(hibernateSettings))
}

/** Builds a [HibernateMetadataConfiguration] with Viaduct's standard policy defaults. */
object HibernateMetadataConfigurationFactory {
    fun create(input: HibernateMetadataConfigurationInput): HibernateMetadataConfiguration =
        HibernateMetadataConfiguration(
            mappingFile = input.mappingFile,
            classpath = input.classpath,
            managedEntityNames = managedEntityNames(input),
            implicitNamingStrategyClassName = input.implicitNamingStrategyClassName,
            physicalNamingStrategyClassName = input.physicalNamingStrategyClassName,
            metadataCustomizerClassNames = input.metadataCustomizerClassNames,
            dialectClassName = input.dialectClassName,
            hibernateSettings = input.hibernateSettings,
            semanticModel = input.semanticModel,
        )

    private fun managedEntityNames(input: HibernateMetadataConfigurationInput): List<String> =
        buildList {
            if (input.semanticModel.retryableTransactions) add(TransactionRecordMapping.ENTITY_NAME)
            input.semanticModel.entities.forEach { entity -> add(entity.graphqlName) }
            input.semanticModel.associations.forEach { association ->
                add(association.typeName)
            }
        }
}
