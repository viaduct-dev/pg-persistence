package dev.viaduct.persistence.orm.grt

import org.hibernate.boot.Metadata
import org.hibernate.dialect.Dialect
import org.hibernate.id.IdentifierGenerator
import org.hibernate.mapping.BasicValue
import org.hibernate.mapping.DependantValue
import org.hibernate.mapping.ManyToOne
import org.hibernate.mapping.SimpleValue
import org.hibernate.tool.schema.extract.spi.ColumnTypeInformation
import org.hibernate.type.BasicArrayType
import org.hibernate.type.BasicType
import org.hibernate.type.CustomType
import org.hibernate.type.SqlTypes
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.AbstractClassJavaType
import org.hibernate.type.descriptor.java.ArrayJavaType
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.descriptor.java.MutabilityPlan
import org.hibernate.type.descriptor.java.UUIDJavaType
import org.hibernate.type.descriptor.jdbc.JdbcType
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators
import org.hibernate.type.spi.TypeConfiguration
import org.hibernate.usertype.UserType
import viaduct.api.globalid.GlobalID
import viaduct.api.types.NodeObject
import java.util.UUID

/** Replace resolved UUID mappings through public boot APIs; keep every existing SQL column/constraint. */
internal fun configureGlobalIds(
    metadata: Metadata,
    bindings: List<NodeGrtBinding<*>>,
) {
    val identifiers =
        bindings.associate { binding ->
            val mapping = requireNotNull(metadata.getEntityBinding(binding.entityName))
            val identifier = globalIdIdentifier(mapping.identifier as BasicValue, binding)
            mapping.rootClass.identifier = identifier
            mapping.identifierProperty.value = identifier
            binding.entityName to identifier
        }
    // Association type resolution must see the new target IDs, including synthetic storage rows.
    metadata.entityBindings.flatMap { it.properties }.forEach { property ->
        val value = property.value
        if (value is ManyToOne) property.value = value.copy()
    }
    // Collection keys otherwise retain the old UUID value even after replacing the owner identifier.
    metadata.collectionBindings.forEach { collection ->
        identifiers[collection.ownerEntityName]?.let { identifier ->
            val original = collection.key as DependantValue
            collection.key = globalIdCollectionKey(original, identifier)
        }
    }
}

private fun globalIdIdentifier(
    original: BasicValue,
    binding: NodeGrtBinding<*>,
): BasicValue {
    val name = "viaduct-global-id:${binding.entityName}"
    val type = GlobalIdHibernateType(binding.idType, original.typeConfiguration)
    original.typeConfiguration.basicTypeRegistry.register(type, name)
    original.typeConfiguration.javaTypeRegistry.addDescriptor(GlobalIdArrayJavaType(type))
    return original.copy().apply {
        typeName = name
        customIdGeneratorCreator =
            org.hibernate.mapping.GeneratorCreator {
                IdentifierGenerator { _, _ -> binding.idType.converter.convertToEntityAttribute(UUID.randomUUID()) }
            }
    }
}

private fun globalIdCollectionKey(
    original: DependantValue,
    identifier: BasicValue,
): DependantValue =
    DependantValue(original.buildingContext, original.table, identifier).apply {
        isNullable = original.isNullable
        isUpdateable = original.isUpdateable
        onDeleteAction = original.onDeleteAction
        foreignKeyName = original.foreignKeyName
        foreignKeyDefinition = original.foreignKeyDefinition
        foreignKeyOptions = original.foreignKeyOptions
        isAlternateUniqueKey = original.isAlternateUniqueKey
        isSorted = original.isSorted
        if (!original.isForeignKeyEnabled) disableForeignKey()
        copyColumns(original, this)
    }

/** Hibernate's array ID loader passes domain IDs directly, unlike scalar bindings and query caches.
 * Keep that array in its domain representation; its element JavaType unwraps each ID to a UUID.
 * Resolution uses the actual element mapping, so erased GlobalID classes cannot mix Node types.
 */
private class GlobalIdArrayJavaType<T : NodeObject>(
    element: BasicType<GlobalID<T>>,
) : ArrayJavaType<GlobalID<T>>(element) {
    override fun resolveType(
        configuration: TypeConfiguration,
        dialect: Dialect,
        elementType: BasicType<GlobalID<T>>,
        columnTypeInformation: ColumnTypeInformation?,
        indicators: JdbcTypeIndicators,
    ): BasicType<*> {
        val jdbc =
            configuration.jdbcTypeRegistry
                .getConstructor(SqlTypes.ARRAY)
                .resolveType(configuration, dialect, elementType, columnTypeInformation)
        return BasicArrayType(elementType, jdbc, ArrayJavaType(elementType))
    }
}

private class GlobalIdHibernateType<T : NodeObject>(
    type: GlobalIdType<T>,
    configuration: TypeConfiguration,
) : CustomType<GlobalID<T>>(type, configuration) {
    // Select the domain array descriptor for native ID loading, while scalar JDBC values are UUIDs.
    private val nativeJava = GlobalIdJavaType(type, super.getMappedJavaType().mutabilityPlan)

    override fun getMappedJavaType(): JavaType<GlobalID<T>> = nativeJava

    override fun getJdbcJavaType(): JavaType<*> = nativeJava

    // Preserve CustomType's descriptor equality: the UserType instance identifies its Node mapping.
    override fun equals(other: Any?): Boolean = other is CustomType<*> && userType == other.userType

    override fun hashCode(): Int = userType.hashCode()
}

/** Public JavaType hook handles both Hibernate's domain ID arrays and relational UUID values. */
private class GlobalIdJavaType<T : NodeObject>(
    type: GlobalIdType<T>,
    plan: MutabilityPlan<GlobalID<T>>,
) : AbstractClassJavaType<GlobalID<T>>(type.returnedClass(), plan) {
    private val equality: UserType<GlobalID<T>> = type
    private val converter = type.valueConverter

    override fun isInstance(value: Any?): Boolean = value is UUID || value is GlobalID<*>

    override fun fromString(value: CharSequence): GlobalID<T> =
        requireNotNull(converter.convertToEntityAttribute(UUID.fromString(value.toString())))

    override fun toString(value: GlobalID<T>): String = converter.convertToDatabaseColumn(value).toString()

    override fun areEqual(
        first: GlobalID<T>?,
        second: GlobalID<T>?,
    ): Boolean = equality.equals(first, second)

    override fun extractHashCode(value: GlobalID<T>): Int = equality.hashCode(value)

    override fun getRecommendedJdbcType(indicators: JdbcTypeIndicators): JdbcType =
        indicators.getJdbcType(SqlTypes.UUID)

    override fun <X> unwrap(
        value: GlobalID<T>?,
        target: Class<X>,
        options: WrapperOptions,
    ): X? =
        when {
            value == null -> null
            target.isInstance(value) -> target.cast(converter.checkedGlobalId(value))
            else -> UUIDJavaType.INSTANCE.unwrap(converter.convertToDatabaseColumn(value), target, options)
        }

    override fun <X> wrap(
        value: X?,
        options: WrapperOptions,
    ): GlobalID<T>? =
        when (value) {
            null -> null
            is GlobalID<*> -> converter.checkedGlobalId(value)
            else -> converter.convertToEntityAttribute(UUIDJavaType.INSTANCE.wrap(value, options))
        }
}

private fun copyColumns(
    source: SimpleValue,
    target: SimpleValue,
) {
    source.columns.forEachIndexed { index, column ->
        target.addColumn(column, source.columnInsertability[index], source.columnUpdateability[index])
    }
}
