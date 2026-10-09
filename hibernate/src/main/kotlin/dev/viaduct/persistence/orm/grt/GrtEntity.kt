package dev.viaduct.persistence.orm.grt

import org.hibernate.Session
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.errors.UnsetFieldException
import java.util.UUID

/**
 * Generated entities use only typed GRT getters/builders. Hibernate owns associations and native
 * identity; each generated subclass stages hydration in its own typed builder. Session-confined.
 */
abstract class GrtEntity<T : NodeObject> {
    abstract val binding: GrtBinding<T>
    abstract val value: T
    private var execution: ResolverExecutionContext<out Query>? = null
    private var nativeId: UUID? = null
    protected val context get() = checkNotNull(execution) { "Entity has no execution context" }

    internal fun attach(context: ResolverExecutionContext<out Query>) {
        check(execution == null) { "Entity already belongs to an execution" }
        execution = context
    }

    internal fun checkContext(context: ResolverExecutionContext<out Query>) {
        require(this.context.requestContext === context.requestContext) { "Entity belongs to another execution" }
    }

    protected abstract fun setGlobalId(id: GlobalID<T>)

    open var internalId: UUID?
        get() = nativeId
        set(value) {
            nativeId = value
            if (value != null) setGlobalId(context.globalIDFor(binding.type, value.toString()))
        }

    /** Existing generated text ID is read-only; use the public resolver-context serializer. */
    open var id: String?
        get() = internalId?.let { context.globalIDStringFor(binding.type, it.toString()) }
        set(value) = Unit

    protected fun <R : NodeObject> globalId(
        target: GrtBinding<R>,
        entity: GrtEntity<R>,
    ): GlobalID<R> = context.globalIDFor(target.type, checkNotNull(entity.internalId).toString())

    protected fun <R : NodeObject> association(
        session: Session,
        target: GrtBinding<R>,
        id: GlobalID<R>?,
    ): GrtEntity<R>? {
        if (id == null) return null
        require(id.type == target.type) { "Wrong relationship target for ${target.type.name}" }
        return target.entityClass.cast(session.getReference(target.type.name, UUID.fromString(id.internalID)))
    }

    /** Generated code reads and validates every required getter before replacing any managed state. */
    abstract fun assign(
        value: T,
        session: Session,
    )

    protected fun validateIdentity(value: T) {
        val supplied = binding.id(value)
        if (nativeId != null) require(supplied != null) { "A replacement must contain its identity" }
        if (supplied != null) {
            require(supplied.type == binding.type) { "Wrong identity type" }
            val id = UUID.fromString(supplied.internalID)
            require(nativeId == null || nativeId == id) { "Wrong identity" }
        }
    }

    /** Called after complete generated validation; no output field is inspected through backing data. */
    protected fun acceptIdentity(value: T) {
        if (nativeId == null) nativeId = binding.id(value)?.let { UUID.fromString(it.internalID) }
    }

    /** A fresh typed builder includes only selected fields and detached relationship references. */
    abstract fun selected(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        fields: Set<String>,
    ): T
}

/**
 * Strict public GRT getters distinguish an omitted field (UnsetFieldException) from an explicit
 * null. Catch only that public exception: policy failures, cancellation, and other errors propagate.
 */
fun isGrtFieldSet(read: () -> Any?): Boolean =
    try {
        read()
        true
    } catch (_: UnsetFieldException) {
        false
    }
