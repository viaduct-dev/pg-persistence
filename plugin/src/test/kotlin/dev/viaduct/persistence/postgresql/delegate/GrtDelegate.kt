@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import viaduct.api.context.ResolverExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.errors.UnsetFieldException

/**
 * Test-only proof of the delegate lifecycle. Production codegen would supply typed constructors
 * and accessors; reflection here only bridges the fixture's randomly named, unchanged GRTs.
 */
internal class DelegateBinding(
    val context: ResolverExecutionContext<Query>,
    val type: Type<NodeObject>,
    val enumClass: Class<*>,
    types: Map<String, Type<NodeObject>>,
) {
    val types: Map<String, Type<NodeObject>> = java.util.Map.copyOf(types)
    private val constructor = type.kcls.java.getConstructor(InternalContext::class.java, EngineObject::class.java)
    private val builderConstructor =
        Class
            .forName("${type.kcls.java.name}\$Builder", true, type.kcls.java.classLoader)
            .getConstructor(viaduct.api.context.ExecutionContext::class.java)

    fun builder(): ObjectBase.Builder<*> = builderConstructor.newInstance(context) as ObjectBase.Builder<*>

    fun wrap(data: EngineObjectData.Sync): ObjectBase {
        val grt = constructor.newInstance(context as InternalContext, data)
        return grt as ObjectBase
    }

    fun enumValue(name: String): Any = enumClass.enumConstants.single { (it as Enum<*>).name == name }
}

/** Scalar state lives in the current GRT, with only a builder while Hibernate is assigning values. */
internal class GrtDelegate(
    val binding: DelegateBinding,
) {
    private var current: ObjectBase? = null
    private var pending: ObjectBase.Builder<*>? = binding.builder()
    var builds: Int = 0
        private set

    val value: ObjectBase
        get() {
            val builder = pending ?: return checkNotNull(current)
            return (builder.build() as ObjectBase).also {
                builds++
                current = it
                pending = null
            }
        }

    fun put(
        field: String,
        value: Any?,
    ) {
        val builder = pending ?: toBuilder(checkNotNull(current)).also { pending = it }
        builder.put(field, value)
    }

    fun replace(value: ObjectBase) {
        require(
            binding.type.kcls.java
                .isInstance(value),
        ) { "Wrong GRT type" }
        current = value
        pending = null
    }

    /** A selected GRT shares immutable backing data; no scalar values are copied or eagerly read. */
    fun selected(
        fields: Set<String>,
        snapshot: ObjectBase = value,
    ): ObjectBase {
        val data = snapshot.__engineObject as EngineObjectData.Sync
        return binding.wrap(SelectedData(data, fields))
    }

    companion object {
        fun toBuilder(value: ObjectBase): ObjectBase.Builder<*> =
            value.javaClass.getMethod("toBuilder").invoke(value) as ObjectBase.Builder<*>
    }
}

/**
 * This is the prototype's only new engine-data implementation. The base is an immutable GRT
 * snapshot, never a managed entity or session. Restrict presence as well as reads so toBuilder()
 * cannot expose unowned base fields through its overlay fallback.
 */
internal class SelectedData(
    internal val base: EngineObjectData.Sync,
    fields: Set<String>,
) : EngineObjectData.Sync {
    private val fields = fields.toSet()
    override val type get() = base.type

    override fun isPresent(selection: String): Boolean = selection in fields && base.isPresent(selection)

    override fun get(selection: String): Any? {
        if (!isPresent(selection)) throw UnsetFieldException(selection, type, "Not owned by this resolver")
        val value = base.get(selection)
        // Viaduct's builder unwraps list elements into a fresh mutable list. Lend a read-only view
        // without copying elements or exposing that snapshot's backing list to engine consumers.
        return if (value is List<*>) java.util.Collections.unmodifiableList(value) else value
    }

    override fun getOrNull(selection: String): Any? = if (isPresent(selection)) get(selection) else null

    override fun getSelections(): Iterable<String> = fields.filter(base::isPresent)

    override suspend fun fetch(selection: String): Any? = get(selection)

    override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()
}
