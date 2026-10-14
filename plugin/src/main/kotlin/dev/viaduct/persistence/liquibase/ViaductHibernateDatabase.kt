package dev.viaduct.persistence.liquibase

import dev.viaduct.persistence.captureException
import dev.viaduct.persistence.hibernate.EffectiveHibernateModel
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfiguration
import dev.viaduct.persistence.hibernate.HibernateMetadataHandle
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlAssociationRelationshipRenderer
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlConstraintRenderer
import liquibase.database.DatabaseConnection
import liquibase.exception.DatabaseException
import liquibase.ext.hibernate.database.HibernateDatabase
import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataSources
import java.io.File

private typealias LiquibaseConnection = DatabaseConnection

@Suppress("TooManyFunctions") // Liquibase lifecycle hooks and desired table/constraint metadata.
class ViaductHibernateDatabase : HibernateDatabase() {
    private var preserveSchemas = false
    internal val preservesSchemas: Boolean get() = preserveSchemas
    private var metadataHandle: HibernateMetadataHandle? = null
    private var effectiveModel: EffectiveHibernateModel? = null

    override fun getSchemaAndCatalogCase(): liquibase.CatalogAndSchema.CatalogAndSchemaCase =
        if (preserveSchemas) {
            liquibase.CatalogAndSchema.CatalogAndSchemaCase.ORIGINAL_CASE
        } else {
            super.getSchemaAndCatalogCase()
        }

    /** The desired pg_graphql `@graphql({...})` comment for a foreign-key column, if known. */
    fun pgGraphqlConstraintComment(
        schemaName: String,
        tableName: String,
        columnName: String,
    ): String? =
        effectiveModel?.let {
            PgGraphqlAssociationRelationshipRenderer.constraintCommentValue(it, schemaName, tableName, columnName)
                ?: PgGraphqlConstraintRenderer.commentValue(it, schemaName, tableName, columnName)
        }

    internal fun pgGraphqlTableComment(
        schemaName: String,
        tableName: String,
    ): String? =
        effectiveModel?.let { model ->
            PgGraphqlAssociationRelationshipRenderer.tableCommentValue(model, schemaName, tableName)
                ?: model.entities
                    .singleOrNull { it.schemaName == schemaName && it.tableName == tableName }
                    ?.let { """@graphql({"name": "${it.graphqlName}"})""" }
        }

    override fun isCorrectDatabaseImplementation(connection: LiquibaseConnection): Boolean {
        val url = connection.url
        return url.startsWith(URL_PREFIX)
    }

    override fun getShortName(): String = "hibernateViaduct"

    override fun getDefaultDatabaseProductName(): String = "Hibernate Viaduct"

    override fun buildMetadataFromPath(): Metadata {
        val path = referencePath()
        val configuration =
            captureException { HibernateMetadataReferences.resolve(path) }
                .getOrElse { failure ->
                    throw DatabaseException(
                        "Unable to resolve Viaduct Hibernate metadata reference $path",
                        failure,
                    )
                }

        return captureException {
            preserveSchemas = configuration.semanticModel?.retryableTransactions == true ||
                configuration.semanticModel?.entities?.any { it.databaseSchema != null } == true
            metadataHandle?.close()
            metadataHandle = null
            effectiveModel = null
            HibernateMetadataBootstrap
                .build(configuration)
                .also { handle ->
                    metadataHandle = handle
                    dialect = handle.metadata.database.jdbcEnvironment.dialect
                    effectiveModel =
                        configuration.semanticModel?.let {
                            EffectiveHibernateModelBuilder.build(handle.metadata, it)
                        }
                }.metadata
        }.getOrElse { failure ->
            throw DatabaseException(
                "Unable to build Hibernate metadata for reference $path",
                failure,
            )
        }
    }

    override fun configureSources(sources: MetadataSources) = Unit

    override fun close() {
        val path = referencePathOrNull()
        try {
            super.close()
        } finally {
            try {
                metadataHandle?.close()
            } finally {
                metadataHandle = null
                effectiveModel = null
                deleteMetadataReference(path)
            }
        }
    }

    private fun referencePath(): String =
        referencePathOrNull()
            ?: throw DatabaseException("Invalid Viaduct Hibernate metadata reference URL")

    private fun referencePathOrNull(): String? =
        captureException {
            hibernateConnection.url
                .takeIf { it.startsWith(URL_PREFIX) }
                ?.removePrefix(URL_PREFIX)
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    companion object {
        const val URL_PREFIX = HIBERNATE_VIADUCT_URL_PREFIX

        fun reference(configuration: HibernateMetadataConfiguration): HibernateMetadataReference =
            HibernateMetadataReferences.create(configuration)
    }
}

private fun deleteMetadataReference(path: String?) {
    val file = path?.let(::File) ?: return
    if (!file.delete() && file.exists()) file.deleteOnExit()
}
