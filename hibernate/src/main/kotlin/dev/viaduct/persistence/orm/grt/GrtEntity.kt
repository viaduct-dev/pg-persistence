package dev.viaduct.persistence.orm.grt

import org.hibernate.Session
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.errors.UnsetFieldException
import java.util.UUID

/**
 * Generated entities use only typed GRT getters/builders. Hibernate owns associations and native
 * identity; each generated subclass stages hydration in its own typed builder. Session-confined.
 */
@Suppress("TooManyFunctions") // Typed snapshots, identity, and execution lifecycle form one delegate contract.
abstract class GrtEntity<T : Object> {
    abstract fun grtBinding(): GrtBinding<T>

    abstract fun grt(): T

    private var execution: ResolverExecutionContext<out Query>? = null
    private var nativeId: UUID? = null

    protected fun executionContext() = checkNotNull(execution) { "Entity has no execution context" }

    internal fun attach(context: ResolverExecutionContext<out Query>) {
        check(execution == null) { "Entity already belongs to an execution" }
        execution = context
    }

    internal fun checkContext(context: ResolverExecutionContext<out Query>) {
        require(executionContext().requestContext === context.requestContext) { "Entity belongs to another execution" }
    }

    protected abstract fun setIdentity(id: UUID)

    open var internalId: UUID?
        get() = nativeId
        set(value) {
            nativeId = value
            if (value != null) setIdentity(value)
        }

    /** Generated code reads and validates every required getter before replacing any managed state. */
    abstract fun assign(
        value: T,
        session: Session,
    )

    protected fun validateIdentity(value: T) {
        val supplied = grtBinding().identityOf(value)
        if (nativeId != null && grtBinding().hasGraphqlIdentity) {
            require(supplied != null) { "A replacement must contain its identity" }
        }
        if (supplied != null) {
            require(nativeId == null || nativeId == supplied) { "Wrong identity" }
        }
    }

    /** Called after complete generated validation; no output field is inspected through backing data. */
    protected fun acceptIdentity(value: T) {
        if (nativeId == null) nativeId = grtBinding().identityOf(value)
    }

    /** Finite public selections bound recursion through ordinary object relationships. */
    open fun project(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        selections: SelectionSet<T>,
    ): T {
        checkContext(context)
        require(session.contains(this)) { "Project a managed entity before its session closes" }
        val fields =
            grtBinding()
                .fields
                .filter { selections.contains(it) }
                .map { it.name }
                .toSet()
        return selected(context, session, fields, selections)
    }

    /** A fresh typed builder includes only selected fields and detached relationship references. */
    abstract fun selected(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        fields: Set<String>,
        selections: SelectionSet<T>? = null,
    ): T
}

/** Only Nodes have a GlobalID and the existing read-only generated text ID column. */
abstract class NodeGrtEntity<T : NodeObject> : GrtEntity<T>() {
    protected abstract fun setGlobalId(id: GlobalID<T>)

    override fun setIdentity(id: UUID) = setGlobalId(executionContext().globalIDFor(grtBinding().type, id.toString()))

    open var id: String?
        get() = internalId?.let { executionContext().globalIDStringFor(grtBinding().type, it.toString()) }
        set(value) = Unit
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
