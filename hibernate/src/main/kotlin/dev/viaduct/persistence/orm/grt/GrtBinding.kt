@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import org.hibernate.boot.Metadata
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Generated, typed constructors and field coordinates; never contains request or session state. */
@Suppress("LongParameterList") // Generated descriptor: constructors, type, and field coordinates belong together.
class GrtBinding<T>(
    val type: Type<T>,
    val entityClass: Class<out GrtEntity<T>>,
    private val newEntity: () -> GrtEntity<T>,
    private val readId: (T) -> GlobalID<T>?,
    fields: List<Field<T>>,
    references: Set<String>,
) where T : NodeObject {
    val fields: List<Field<T>> = java.util.List.copyOf(fields)
    val references: Set<String> = java.util.Set.copyOf(references)

    fun create(context: ResolverExecutionContext<out Query>): GrtEntity<T> = newEntity().also { it.attach(context) }

    internal fun id(value: T): GlobalID<T>? = readId(value)
}

/** Explicit opt-in: changes Java representations only, leaving schema-generation metadata alone. */
class GrtBindings(
    bindings: List<GrtBinding<*>>,
    connections: List<GrtConnectionBinding<*, *>> = emptyList(),
) {
    private val bindings =
        bindings.associateBy { it.type.name }.also {
            require(it.size == bindings.size) { "Duplicate delegate type" }
        }

    private val connections =
        connections.associateBy { it.type.name }.also {
            require(it.size == connections.size) { "Duplicate connection type" }
        }

    fun configure(metadata: Metadata) {
        // Check everything before mutating metadata. Replaced/custom mappings must not silently lose
        // properties simply because the generated Java representation lacks an accessor.
        bindings.values.forEach { binding ->
            val mapping = requireNotNull(metadata.getEntityBinding(binding.type.name))
            val names = mapping.properties.map { it.name } + mapping.identifierProperty.name
            val properties =
                java.beans.Introspector
                    .getBeanInfo(binding.entityClass)
                    .propertyDescriptors
            require(
                names.all { name ->
                    properties.any {
                        it.name == name &&
                            it.readMethod != null &&
                            it.writeMethod != null
                    }
                },
            ) {
                "Mapping properties do not match generated delegate ${binding.type.name}"
            }
        }
        bindings.values.forEach { binding ->
            metadata.getEntityBinding(binding.type.name).apply {
                className = binding.entityClass.name
                proxyInterfaceName = binding.entityClass.name
                isLazy = true
            }
        }
    }

    internal fun byName(name: String): GrtBinding<*>? = bindings[name]

    @Suppress("UNCHECKED_CAST") // Generated connection Type and builder are paired by class identity.
    internal fun <R : viaduct.api.types.Connection<*, *>> connection(type: Type<R>): GrtConnectionBinding<R, *> {
        val binding = requireNotNull(connections[type.name]) { "No generated connection for ${type.name}" }
        require(binding.type.kcls == type.kcls) { "Connection class does not match ${type.name}" }
        return binding as GrtConnectionBinding<R, *>
    }

    @Suppress("UNCHECKED_CAST") // The class check establishes the generated binding's type.
    internal fun <T : NodeObject> forValue(value: T): GrtBinding<T> =
        requireNotNull(bindings.values.singleOrNull { it.type.kcls.java == value.javaClass }) {
            "No delegate for ${value.javaClass.name}"
        } as GrtBinding<T>

    @Suppress("UNCHECKED_CAST") // A generated binding contains the exact Type and GRT class it constructs.
    internal fun <T : NodeObject> forType(type: Type<T>): GrtBinding<T> {
        val binding = requireNotNull(bindings[type.name]) { "No delegate for ${type.name}" }
        require(binding.type.kcls == type.kcls) { "GRT class does not match delegate ${type.name}" }
        return binding as GrtBinding<T>
    }
}
