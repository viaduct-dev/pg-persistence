@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import org.hibernate.Interceptor
import org.hibernate.boot.Metadata
import org.hibernate.metamodel.spi.EntityRepresentationStrategy
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import java.util.UUID

/** Generated, typed constructors and field coordinates; never contains request or session state. */
@Suppress("LongParameterList") // Generated descriptor: constructors, type, and field coordinates belong together.
open class GrtBinding<T>(
    val type: Type<T>,
    override val entityClass: Class<out GrtEntity<T>>,
    private val newEntity: () -> GrtEntity<T>,
    private val readIdentity: (T) -> Any?,
    fields: List<Field<T>>,
    references: Set<String>,
    override val entityName: String = type.name,
    val hasGraphqlIdentity: Boolean = true,
    val reader: GrtReadProjection<T>? = null,
) : NativeBinding where T : Object {
    val fields: List<Field<T>> = java.util.List.copyOf(fields)
    val references: Set<String> = java.util.Set.copyOf(references)

    fun create(context: ResolverExecutionContext<out Query>): GrtEntity<T> = newEntity().also { it.attach(context) }

    open fun identityOf(value: T): Any? = readIdentity(value)

    open fun checkedIdentity(value: Any): Any = value

    override fun instantiate(
        context: ResolverExecutionContext<out Query>,
        id: Any?,
    ): Any = create(context).also { it.initializeIdentity(id) }
}

/** Node identities use Viaduct GlobalIDs; ordinary object identities do not. */
@Suppress("LongParameterList") // Generated descriptor keeps identity and field coordinates together.
class NodeGrtBinding<T : NodeObject>(
    type: Type<T>,
    entityClass: Class<out GrtEntity<T>>,
    newEntity: () -> GrtEntity<T>,
    readId: (T) -> GlobalID<T>?,
    fields: List<Field<T>>,
    references: Set<String>,
    reader: GrtReadProjection<T>? = null,
) : GrtBinding<T>(
        type,
        entityClass,
        newEntity,
        readId,
        fields,
        references,
        reader = reader,
    ) {
    val idType = GlobalIdType(type)
    val idClass: Class<GlobalID<T>> = idType.returnedClass()

    override fun identityOf(value: T): GlobalID<T>? = super.identityOf(value)?.let(::checkedIdentity)

    override fun checkedIdentity(value: Any): GlobalID<T> = idType.converter.checkedGlobalId(value)
}

/** A native storage row has no GraphQL identity or invented GRT. */
interface NativeBinding {
    val entityName: String
    val entityClass: Class<*>

    fun instantiate(
        context: ResolverExecutionContext<out Query>,
        id: Any?,
    ): Any
}

class StorageBinding(
    override val entityName: String,
    override val entityClass: Class<*>,
    private val create: (UUID?) -> Any,
) : NativeBinding {
    override fun instantiate(
        context: ResolverExecutionContext<out Query>,
        id: Any?,
    ): Any = create(id as UUID?)
}

/** Explicit opt-in: changes Java representations only, leaving schema-generation metadata alone. */
class GrtBindings(
    bindings: List<GrtBinding<*>>,
    connections: List<GrtConnectionBinding<*, *>> = emptyList(),
    storage: List<StorageBinding> = emptyList(),
) {
    private val bindings =
        (bindings + storage).associateBy { it.entityName }.also {
            require(it.size == bindings.size + storage.size) { "Duplicate delegate type" }
        }

    private val connections =
        connections.associateBy { it.type.name }.also {
            require(it.size == connections.size) { "Duplicate connection type" }
        }

    /** Context-aware hydration on a native SessionBuilder; no Session or transaction facade. */
    fun interceptor(context: ResolverExecutionContext<out Query>): Interceptor =
        object : Interceptor {
            override fun instantiate(
                entityName: String,
                representationStrategy: EntityRepresentationStrategy,
                id: Any?,
            ): Any? = byName(entityName)?.instantiate(context, id)
        }

    fun configure(metadata: Metadata) {
        // Check everything before mutating metadata. Replaced/custom mappings must not silently lose
        // properties simply because the generated Java representation lacks an accessor.
        bindings.values.forEach { binding ->
            val mapping = requireNotNull(metadata.getEntityBinding(binding.entityName))
            val mapped = mapping.properties + mapping.identifierProperty
            val properties =
                java.beans.Introspector
                    .getBeanInfo(binding.entityClass)
                    .propertyDescriptors
            require(
                mapped.all { property ->
                    property.propertyAccessStrategy?.let {
                        it.buildPropertyAccess(binding.entityClass, property.name, true).setter != null
                    } ?: properties.any {
                        it.name == property.name &&
                            it.readMethod != null &&
                            it.writeMethod != null
                    }
                },
            ) {
                "Mapping properties do not match generated delegate ${binding.entityName}"
            }
        }
        configureGlobalIds(metadata, bindings.values.filterIsInstance<NodeGrtBinding<*>>())
        bindings.values.forEach { binding ->
            metadata.getEntityBinding(binding.entityName).apply {
                className = binding.entityClass.name
                proxyInterfaceName = binding.entityClass.name
                isLazy = true
            }
        }
    }

    internal fun byName(name: String): NativeBinding? = bindings[name]

    @Suppress("UNCHECKED_CAST") // Generated connection Type and builder are paired by class identity.
    internal fun <R : viaduct.api.types.Connection<*, *>> connection(type: Type<R>): GrtConnectionBinding<R, *> {
        val binding = requireNotNull(connections[type.name]) { "No generated connection for ${type.name}" }
        require(binding.type.kcls == type.kcls) { "Connection class does not match ${type.name}" }
        return binding as GrtConnectionBinding<R, *>
    }

    @Suppress("UNCHECKED_CAST") // A generated binding contains the exact Type and GRT class it constructs.
    internal fun <T : Object> forType(type: Type<T>): GrtBinding<T> {
        val binding = requireNotNull(bindings[type.name] as? GrtBinding<*>) { "No delegate for ${type.name}" }
        require(binding.type.kcls == type.kcls) { "GRT class does not match delegate ${type.name}" }
        return binding as GrtBinding<T>
    }
}
