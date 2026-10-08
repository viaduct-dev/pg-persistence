package dev.viaduct.persistence.orm

import org.hibernate.boot.model.TypeContributions
import org.hibernate.boot.model.TypeContributor
import org.hibernate.dialect.Dialect
import org.hibernate.engine.jdbc.spi.JdbcServices
import org.hibernate.service.ServiceRegistry
import org.hibernate.type.BasicArrayType
import org.hibernate.type.BasicTypeReference
import org.hibernate.type.SqlTypes
import org.hibernate.type.StandardBasicTypes
import org.hibernate.type.descriptor.java.ArrayJavaType

/** Registers native Hibernate array mappings used by schema-generated HBM and runtime sessions. */
class ViaductArrayTypes : TypeContributor {
    override fun contribute(
        contributions: TypeContributions,
        serviceRegistry: ServiceRegistry,
    ) {
        val dialect = requireNotNull(serviceRegistry.getService(JdbcServices::class.java)).dialect
        listOf(
            StandardBasicTypes.STRING,
            StandardBasicTypes.BOOLEAN,
            StandardBasicTypes.BYTE,
            StandardBasicTypes.SHORT,
            StandardBasicTypes.INTEGER,
            StandardBasicTypes.LONG,
            StandardBasicTypes.DOUBLE,
            StandardBasicTypes.UUID,
            StandardBasicTypes.LOCAL_DATE,
            StandardBasicTypes.LOCAL_TIME,
            StandardBasicTypes.OFFSET_DATE_TIME,
            StandardBasicTypes.BIG_DECIMAL,
            StandardBasicTypes.INSTANT,
        ).forEach { contributeArray(contributions, dialect, it) }
        val configuration = contributions.typeConfiguration
        val json =
            configuration.basicTypeRegistry.resolve(
                org.hibernate.type.descriptor.java.spi
                    .JsonJavaType<Any>(Any::class.java, null, configuration),
                configuration.jdbcTypeRegistry.getDescriptor(SqlTypes.JSON),
            )
        contributions.contributeType(json, "viaduct-json")
    }

    @Suppress("DEPRECATION")
    private fun <T> contributeArray(
        contributions: TypeContributions,
        dialect: Dialect,
        reference: BasicTypeReference<T>,
    ) {
        val configuration = contributions.typeConfiguration
        val element = configuration.basicTypeRegistry.resolve(reference)
        val jdbc =
            configuration.jdbcTypeRegistry
                .getConstructor(SqlTypes.ARRAY)
                .resolveType(configuration, dialect, element, null)
        val java = ArrayJavaType(element)
        contributions.contributeType(BasicArrayType(element, jdbc, java), java.javaTypeClass.name)
    }
}
