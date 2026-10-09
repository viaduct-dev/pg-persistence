package dev.viaduct.persistence.orm

import org.hibernate.type.BasicType
import org.hibernate.type.descriptor.ValueExtractor
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.descriptor.jdbc.BasicExtractor
import org.hibernate.type.descriptor.jdbc.JdbcType
import java.sql.CallableStatement
import java.sql.ResultSet
import java.lang.reflect.Array as JavaArray

/**
 * Read special array elements with their normal Hibernate scalar extractor. pgjdbc's getArray()
 * loses timetz offsets, and Hibernate's aggregate-array path assumes JSON embeddable records.
 * Hibernate still supplies array binding, SQL generation, element conversion, and dirty checking.
 */
internal class NativeArrayJdbcType(
    native: JdbcType,
    private val element: BasicType<*>,
) : JdbcType by native {
    override fun <X> getExtractor(javaType: JavaType<X>): ValueExtractor<X> =
        object : BasicExtractor<X>(javaType, this) {
            override fun doExtract(
                rs: ResultSet,
                index: Int,
                options: WrapperOptions,
            ): X? = readArray(rs.getArray(index), javaType, options)

            override fun doExtract(
                statement: CallableStatement,
                index: Int,
                options: WrapperOptions,
            ): X? = readArray(statement.getArray(index), javaType, options)

            override fun doExtract(
                statement: CallableStatement,
                name: String,
                options: WrapperOptions,
            ): X? = readArray(statement.getArray(name), javaType, options)
        }

    private fun <X> readArray(
        array: java.sql.Array?,
        javaType: JavaType<X>,
        options: WrapperOptions,
    ): X? {
        if (array == null) return null
        try {
            val values = mutableListOf<Any?>()
            val extractor = element.jdbcType.getExtractor(element.javaTypeDescriptor)
            array.resultSet.use { rows ->
                while (rows.next()) values.add(extractor.extract(rows, 2, options))
            }
            // Elements are already decoded. ArrayJavaType.wrap would try to decode them again.
            val typed = JavaArray.newInstance(element.javaTypeDescriptor.javaTypeClass, values.size)
            values.forEachIndexed { index, value -> JavaArray.set(typed, index, value) }
            return javaType.javaTypeClass.cast(typed)
        } finally {
            array.free()
        }
    }
}
