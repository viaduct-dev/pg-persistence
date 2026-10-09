package dev.viaduct.persistence.orm

import org.hibernate.type.SqlTypes
import org.hibernate.type.descriptor.ValueBinder
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.descriptor.jdbc.BasicBinder
import org.hibernate.type.descriptor.jdbc.JdbcType
import org.hibernate.type.descriptor.jdbc.TimeWithTimeZoneJdbcType
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.Types
import java.time.OffsetTime

/** pgjdbc accepts OffsetTime through setObject, but rejects the JDBC TIME_WITH_TIMEZONE type code. */
internal object PostgresqlOffsetTimeJdbcType : JdbcType by TimeWithTimeZoneJdbcType.INSTANCE {
    override fun getJdbcTypeCode(): Int = Types.TIME

    override fun getDefaultSqlTypeCode(): Int = SqlTypes.TIME_WITH_TIMEZONE

    override fun getDdlTypeCode(): Int = SqlTypes.TIME_WITH_TIMEZONE

    override fun getPreferredJavaTypeClass(options: WrapperOptions): Class<*> = OffsetTime::class.java

    override fun <X> getBinder(javaType: JavaType<X>): ValueBinder<X> =
        object : BasicBinder<X>(javaType, this) {
            override fun doBind(
                statement: PreparedStatement,
                value: X,
                index: Int,
                options: WrapperOptions,
            ) {
                statement.setObject(index, javaType.unwrap(value, OffsetTime::class.java, options))
            }

            override fun doBind(
                statement: CallableStatement,
                value: X,
                name: String,
                options: WrapperOptions,
            ) {
                statement.setObject(name, javaType.unwrap(value, OffsetTime::class.java, options))
            }
        }
}
