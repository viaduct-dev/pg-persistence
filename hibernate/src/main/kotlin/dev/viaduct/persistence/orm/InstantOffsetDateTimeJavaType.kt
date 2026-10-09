package dev.viaduct.persistence.orm

import org.hibernate.type.descriptor.java.OffsetDateTimeJavaType
import java.time.OffsetDateTime

/**
 * PostgreSQL timestamptz stores an instant, not the submitted offset. JDBC array extraction may
 * use the JVM's local offset, while Viaduct DateTime getters return Instant. Compare and hash by
 * instant so public GRT hydration does not make unchanged timestamp arrays appear dirty.
 */
internal class InstantOffsetDateTimeJavaType : OffsetDateTimeJavaType() {
    override fun areEqual(
        one: OffsetDateTime?,
        another: OffsetDateTime?,
    ): Boolean = one?.toInstant() == another?.toInstant()

    override fun extractHashCode(value: OffsetDateTime): Int = value.toInstant().hashCode()

    override fun useObjectEqualsHashCode(): Boolean = false
}
