package dev.viaduct.persistence.orm

import org.hibernate.type.SqlTypes
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.descriptor.jdbc.JdbcType
import org.hibernate.type.descriptor.sql.DdlType

/** PostgreSQL supports timetz, including arrays, even though Hibernate omits its DDL descriptor. */
internal object PostgresqlOffsetTimeDdlType : DdlType {
    override fun getSqlTypeCode(): Int = SqlTypes.TIME_WITH_TIMEZONE

    override fun getRawTypeName(): String = "timetz"

    override fun getTypeName(
        length: Long?,
        precision: Int?,
        scale: Int?,
    ): String = "timetz"

    override fun getCastTypeName(
        jdbcType: JdbcType,
        javaType: JavaType<*>,
    ): String = "timetz"

    override fun getCastTypeName(
        jdbcType: JdbcType,
        javaType: JavaType<*>,
        length: Long?,
        precision: Int?,
        scale: Int?,
    ): String = "timetz"
}
