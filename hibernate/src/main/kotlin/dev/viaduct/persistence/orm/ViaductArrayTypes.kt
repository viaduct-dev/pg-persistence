package dev.viaduct.persistence.orm

import org.hibernate.boot.model.TypeContributions
import org.hibernate.boot.model.TypeContributor
import org.hibernate.dialect.Dialect
import org.hibernate.dialect.PostgreSQLDialect
import org.hibernate.engine.jdbc.spi.JdbcServices
import org.hibernate.service.ServiceRegistry
import org.hibernate.type.BasicArrayType
import org.hibernate.type.BasicType
import org.hibernate.type.BasicTypeReference
import org.hibernate.type.SqlTypes
import org.hibernate.type.StandardBasicTypes
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.ArrayJavaType
import org.hibernate.type.descriptor.java.OffsetTimeJavaType
import org.hibernate.type.descriptor.jdbc.JdbcType

/** Registers native Hibernate array mappings used by schema-generated HBM and runtime sessions. */
class ViaductArrayTypes : TypeContributor {
    override fun contribute(
        contributions: TypeContributions,
        serviceRegistry: ServiceRegistry,
    ) {
        val dialect = requireNotNull(serviceRegistry.getService(JdbcServices::class.java)).dialect
        if (dialect is PostgreSQLDialect) {
            contributions.typeConfiguration.ddlTypeRegistry.addDescriptorIfAbsent(PostgresqlOffsetTimeDdlType)
            val time =
                contributions.typeConfiguration.basicTypeRegistry.resolve(
                    OffsetTimeJavaType.INSTANCE,
                    PostgresqlOffsetTimeJdbcType,
                )
            contributions.contributeType(time, "OffsetTimeWithTimezone")
        }
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
            StandardBasicTypes.OFFSET_TIME_WITH_TIMEZONE,
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
        // SQL arrays contain basic JSON values, not Hibernate embeddable aggregates. Let the
        // JSON JavaType handle format conversion while the array binder uses JDBC strings.
        val jsonElementJdbc =
            object : JdbcType by json.jdbcType {
                override fun getDefaultSqlTypeCode(): Int = SqlTypes.JSON

                override fun getDdlTypeCode(): Int = SqlTypes.JSON

                override fun getPreferredJavaTypeClass(options: WrapperOptions): Class<*> = String::class.java
            }
        val jsonElement = configuration.basicTypeRegistry.resolve(json.javaTypeDescriptor, jsonElementJdbc)
        contributeArray(contributions, dialect, jsonElement, "viaduct-json-array")
    }

    @Suppress("DEPRECATION")
    private fun <T> contributeArray(
        contributions: TypeContributions,
        dialect: Dialect,
        reference: BasicTypeReference<T>,
    ) {
        val configuration = contributions.typeConfiguration
        val standard = requireNotNull(configuration.basicTypeRegistry.resolve(reference))
        val element =
            if (reference == StandardBasicTypes.OFFSET_DATE_TIME) {
                configuration.basicTypeRegistry.resolve(InstantOffsetDateTimeJavaType(), standard.jdbcType)
            } else {
                standard
            }
        contributeArray(contributions, dialect, element, ArrayJavaType(element).javaTypeClass.name)
    }

    /** JSON arrays need their JSON element descriptor; a String array would store JSON as text. */
    @Suppress("DEPRECATION")
    private fun <T> contributeArray(
        contributions: TypeContributions,
        dialect: Dialect,
        element: BasicType<T>,
        name: String,
    ) {
        val configuration = contributions.typeConfiguration
        val jdbc =
            configuration.jdbcTypeRegistry
                .getConstructor(SqlTypes.ARRAY)
                .resolveType(configuration, dialect, element, null)
        val arrayJdbc =
            when (element.jdbcType.ddlTypeCode) {
                SqlTypes.JSON, SqlTypes.TIME_WITH_TIMEZONE ->
                    NativeArrayJdbcType(jdbc, element)
                else ->
                    jdbc
            }
        val java = ArrayJavaType(element)
        contributions.contributeType(BasicArrayType(element, arrayJdbc, java), name)
    }
}
