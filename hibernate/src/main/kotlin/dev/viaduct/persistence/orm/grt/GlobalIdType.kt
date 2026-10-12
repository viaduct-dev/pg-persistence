package dev.viaduct.persistence.orm.grt

import jakarta.persistence.AttributeConverter
import org.hibernate.usertype.UserType
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import java.io.Serializable
import java.util.UUID

/** Hibernate equality, immutability, and caching use the same UUID representation as JDBC. */
class GlobalIdType<T : NodeObject>(
    type: Type<T>,
) : UserType<GlobalID<T>> {
    internal val converter = GlobalIdConverter(type)

    override fun getValueConverter(): AttributeConverter<GlobalID<T>, UUID> = converter

    @Suppress("UNCHECKED_CAST") // GlobalID's erased Java class is paired with this mapping's concrete Node type.
    override fun returnedClass(): Class<GlobalID<T>> = GlobalID::class.java as Class<GlobalID<T>>

    override fun getSqlType(): Int = org.hibernate.type.SqlTypes.UUID

    override fun isMutable(): Boolean = false

    override fun deepCopy(value: GlobalID<T>?): GlobalID<T>? = value

    // UUID equality keeps alternate spellings compatible with the previous UUID-backed lookups.
    override fun equals(
        first: GlobalID<T>?,
        second: GlobalID<T>?,
    ): Boolean = converter.convertToDatabaseColumn(first) == converter.convertToDatabaseColumn(second)

    override fun hashCode(value: GlobalID<T>): Int = converter.convertToDatabaseColumn(value).hashCode()

    // GlobalID/Type are not Serializable. Cache only the native UUID; the mapping restores its type.
    override fun disassemble(value: GlobalID<T>?): Serializable? = converter.convertToDatabaseColumn(value)

    override fun assemble(
        cached: Serializable?,
        owner: Any?,
    ): GlobalID<T>? = converter.convertToEntityAttribute(cached as UUID?)
}

/** Stateless Node validation and UUID conversion, independent of Hibernate sessions and requests. */
internal class GlobalIdConverter<T : NodeObject>(
    private val type: Type<T>,
) : AttributeConverter<GlobalID<T>, UUID> {
    override fun convertToDatabaseColumn(value: GlobalID<T>?): UUID? =
        value?.let {
            require(it.type == type) { "Expected ${type.name} identity, got ${it.type.name}" }
            UUID.fromString(it.internalID)
        }

    override fun convertToEntityAttribute(value: UUID?): GlobalID<T>? = value?.let { GlobalID(type, it.toString()) }
}

/** Validate erased IDs through their mapped converter, preserving already canonical values. */
@Suppress("UNCHECKED_CAST") // Conversion validates the concrete Node type after this erased cast.
internal fun <T : NodeObject> AttributeConverter<GlobalID<T>, UUID>.checkedGlobalId(value: Any): GlobalID<T> {
    require(value is GlobalID<*>) { "Expected GlobalID" }
    val id = value as GlobalID<T>
    val uuid = requireNotNull(convertToDatabaseColumn(id))
    return if (id.internalID == uuid.toString()) id else requireNotNull(convertToEntityAttribute(uuid))
}
