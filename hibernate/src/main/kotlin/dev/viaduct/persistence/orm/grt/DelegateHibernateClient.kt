@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.hibernate.Hibernate
import org.hibernate.Interceptor
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.metamodel.spi.EntityRepresentationStrategy
import org.hibernate.query.SelectionQuery
import viaduct.api.FieldValue
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import java.util.UUID

/**
 * Optional GRT-backed Hibernate path. The existing persistence client remains available.
 * Hibernate handles native queries and dirty checking; this bridge supplies request-bound entity
 * construction and detached, selective GRT results. No pg_graphql protocol or projection is used.
 */
@Suppress("TooManyFunctions") // Native operations and Node/connection helpers share execution ownership checks.
class DelegateHibernateClient(
    private val factory: SessionFactory,
    private val bindings: GrtBindings,
    private val initializeSession: (Session, ResolverExecutionContext<out Query>) -> Unit,
) {
    private val owner = Any()

    /**
     * Keep the callback synchronous and session-confined. Cancellation before commit rolls back,
     * but blocking JDBC needs database/driver timeouts. Return GRTs rather than lazy native entities.
     */
    @Suppress("TooGenericExceptionCaught") // Roll back on every failure and rethrow it without translation.
    suspend fun <R> transaction(
        context: ResolverExecutionContext<out Query>,
        block: (Session) -> R,
    ): R =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            val interceptor = DelegateInterceptor(bindings, context)
            factory.withOptions().interceptor(interceptor).openSession().use { native ->
                val session = DelegateSession(native, owner, context.requestContext)
                val tx = session.beginTransaction()
                try {
                    initializeSession(session, context)
                    block(session).also {
                        coroutine.ensureActive()
                        tx.commit()
                    }
                } catch (failure: Throwable) {
                    // Cleanup must also run for cancellation and fatal failures; never turn them into
                    // GraphQL field errors. Preserve the original failure if rollback itself fails.
                    if (tx.isActive) runCatching { tx.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
        }

    fun <T> insert(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        value: T,
    ): T
        where T : Object {
        checkDelegateSession(factory, owner, context, session)
        val binding = bindings.forValue(value)
        val entity = binding.create(context)
        entity.assign(value, session)
        session.persist(binding.entityName, entity)
        return entity.grt()
    }

    /** A complete write snapshot contains scalars and owning to-one references, never collections. */
    fun <T> find(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        id: GlobalID<T>,
    ): T
        where T : NodeObject = entity(context, session, id).grt()

    fun <T> update(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        value: T,
    ): T
        where T : NodeObject {
        val binding = bindings.forValue(value)
        val key = requireNotNull(binding.identityOf(value)) { "A replacement must contain its identity" }
        val id = context.globalIDFor(binding.type, key.toString())
        val entity = entity(context, session, id)
        entity.assign(value, session)
        return entity.grt()
    }

    fun <T> delete(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        id: GlobalID<T>,
    )
        where T : NodeObject = session.remove(entity(context, session, id))

    fun <T> select(
        context: SelectiveNodeExecutionContext<T>,
        session: Session,
        entity: GrtEntity<T>,
    ): T
        where T : NodeObject {
        checkDelegateSession(factory, owner, context, session)
        require(session.contains(entity)) { "Select a managed entity before its session closes" }
        require(entity.grtBinding().type == context.id.type && entity.internalId.toString() == context.id.internalID) {
            "Entity does not match the requested node"
        }
        entity.checkContext(context)
        val owned = context.ownedSelections()
        val requested = context.selections()
        val fields =
            entity
                .grtBinding()
                .fields
                .filter {
                    owned.contains(it) || it.name in entity.grtBinding().references && requested.contains(it)
                }.map { it.name }
                .toSet()
        return entity.selected(context, session, fields, requested)
    }

    /** Project an ordinary concrete object inside its native transaction; no Node identity is required. */
    fun <T : Object> project(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        entity: GrtEntity<T>,
        selections: SelectionSet<T>,
    ): T {
        checkDelegateSession(factory, owner, context, session)
        return entity.project(context, session, selections)
    }

    suspend fun <T> fetchNode(context: SelectiveNodeExecutionContext<T>): T
        where T : NodeObject =
        transaction(context) { session -> select(context, session, entity(context, session, context.id)) }

    /** Batch loading retains a separate selective GRT view for every resolver context. */
    suspend fun <T, C> fetchNodes(
        contexts: List<C>,
    ): Map<C, FieldValue<T>>
        where T : NodeObject, C : SelectiveNodeExecutionContext<T> {
        if (contexts.isEmpty()) return emptyMap()
        require(contexts.all { it.requestContext === contexts.first().requestContext }) {
            "Batch contexts must belong to the same execution"
        }
        return transaction(contexts.first()) { session ->
            contexts
                .groupBy { it.id.type }
                .flatMap { (type, group) ->
                    val binding = bindings.forType(type)
                    val ids = group.map { UUID.fromString(it.id.internalID) }.distinct()
                    val entities =
                        session
                            .byMultipleIds<Any>(type.name)
                            .multiLoad(ids)
                            .filterNotNull()
                            .associateBy { session.getIdentifier(it) }
                    group.map { context ->
                        val entity = entities[UUID.fromString(context.id.internalID)]
                        context to
                            if (entity == null) {
                                FieldValue.ofError(
                                    IllegalStateException("${type.name} '${context.id.internalID}' was not found"),
                                )
                            } else {
                                delegateNodeResult {
                                    select(
                                        context,
                                        session,
                                        binding.entityClass.cast(Hibernate.unproxy(entity)),
                                    )
                                }
                            }
                    }
                }.toMap()
        }
    }

    /** Supply an unpaged node UUID/entity query with deterministic ordering and an identifier tie-breaker. */
    suspend fun <R : Connection<*, *>> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        selections: SelectionSet<R>,
        query: (Session) -> SelectionQuery<*>,
    ): R {
        context.arguments.validate()
        val binding = bindings.connection(selections.type)
        return transaction(context) { session ->
            val selection = query(session)
            require(
                selection.firstResult == 0 && selection.maxResults == Int.MAX_VALUE,
            ) { "Connection queries must be unpaged" }
            val bounds =
                if (context.arguments.requiresTotalCountForOffsetLimit()) {
                    context.arguments.toOffsetLimit(totalCount = Math.toIntExact(selection.resultCount))
                } else {
                    context.arguments.toOffsetLimit()
                }
            val rows = selection.setFirstResult(bounds.offset).setMaxResults(Math.addExact(bounds.limit, 1)).resultList
            binding.build(context, session, rows, bounds, selections)
        }
    }

    private fun <T> entity(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        id: GlobalID<T>,
    ): GrtEntity<T>
        where T : NodeObject {
        checkDelegateSession(factory, owner, context, session)
        val binding = bindings.forType(id.type)
        val entity =
            requireNotNull(session.find(id.type.name, UUID.fromString(id.internalID))) {
                "${id.type.name} '${id.internalID}' was not found"
            }
        return binding.entityClass.cast(Hibernate.unproxy(entity)).also { it.checkContext(context) }
    }
}

@Suppress("TooGenericExceptionCaught") // Only ordinary per-node failures become FieldValue errors.
private inline fun <T> delegateNodeResult(block: () -> T): FieldValue<T> =
    try {
        FieldValue.ofValue(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        FieldValue.ofError(failure)
    }

/** Hibernate's per-session interceptor holds execution identity; no thread-local or shared request state. */
private class DelegateInterceptor(
    private val bindings: GrtBindings,
    val context: ResolverExecutionContext<out Query>,
) : Interceptor {
    override fun instantiate(
        entityName: String,
        representationStrategy: EntityRepresentationStrategy,
        id: Any?,
    ): Any? = bindings.byName(entityName)?.instantiate(context, id as UUID?)
}

/** Private carrier over the public Session API; no shared registry or implementation inspection. */
private class DelegateSession(
    native: Session,
    val owner: Any,
    val requestContext: Any?,
) : Session by native

private fun checkDelegateSession(
    factory: SessionFactory,
    owner: Any,
    context: ResolverExecutionContext<out Query>,
    session: Session,
) {
    require(
        session is DelegateSession &&
            session.sessionFactory === factory &&
            session.owner === owner &&
            session.requestContext === context.requestContext,
    ) { "Use this client's transaction and the same execution context" }
}
