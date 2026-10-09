@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineObjectData
import java.util.UUID

/** Common identity/hydration plumbing for the handwritten stand-ins for generated delegates. */
internal abstract class DelegateEntity {
    private var delegate: GrtDelegate? = null
    private var nativeId: UUID? = null
    protected val state: GrtDelegate
        get() = checkNotNull(delegate) { "Only Hibernate proxies may be constructed without a binding" }
    protected open val nativeFields: Set<String> = emptySet()

    protected fun bind(binding: DelegateBinding) {
        delegate = GrtDelegate(binding)
    }

    internal val value: ObjectBase get() = state.value
    internal val builds: Int get() = state.builds
    internal val requestContext: Any? get() = state.binding.context.requestContext

    internal fun initialize(value: ObjectBase) {
        check(nativeId == null) { "A managed delegate must use validated replacement" }
        val data = value.__engineObject as EngineObjectData.Sync
        require(nativeFields.none { data.isPresent(it) && data.get(it) != null }) {
            "Assign relationships through native Hibernate associations"
        }
        state.replace(value)
    }

    internal open fun selected(fields: Set<String>): ObjectBase = state.selected(fields)

    open var internalId: UUID?
        get() = nativeId
        set(value) {
            nativeId = value
            if (value != null) state.put("id", state.binding.context.globalIDFor(state.binding.type, value.toString()))
        }

    // The generated text column is read-only. GRT identity comes from the native UUID.
    open var id: String?
        get() =
            internalId?.let {
                (state.binding.context as InternalContext)
                    .globalIDCodec
                    .serialize(state.binding.type.name, it.toString())
            }
        set(value) = Unit

    /** Reading a proxy's identifier does not require hydrating its scalar fields. */
    protected fun reference(
        target: String,
        entity: DelegateEntity,
    ): NodeObject {
        val type = state.binding.types.getValue(target)
        val id: GlobalID<NodeObject> =
            state.binding.context.globalIDFor(type, checkNotNull(entity.internalId).toString())
        return state.binding.context.ref(id)
    }
}
