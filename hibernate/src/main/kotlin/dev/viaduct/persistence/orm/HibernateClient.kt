@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm

import dev.viaduct.persistence.runtime.grt.GrtConnection
import dev.viaduct.persistence.runtime.grt.GrtProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.query.SelectionQuery
import viaduct.api.FieldValue
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import java.lang.reflect.InvocationTargetException

/**
 * Hibernate owns queries, managed entities, writes, and transactions. This client only supplies
 * transaction boundaries and builds detached Viaduct results before the session closes.
 * [initializeSession] must establish the application's trusted identity/role inside each transaction.
 */
class HibernateClient(
    private val factory: SessionFactory,
    private val initializeSession: (Session, ExecutionContext) -> Unit,
) {
    private val projection = GrtProjection()
    private val connections = GrtConnection()

    /**
     * The block is deliberately synchronous: a Hibernate session must not be used concurrently or
     * moved between coroutine threads. Use Hibernate persist/remove, dirty checking, HQL/Criteria,
     * or doWork here. Return detached values (or [project] results), not lazy managed entities.
     * Cancellation observed before commit rolls back; blocking SQL needs normal JDBC timeouts.
     */
    suspend fun <T> transaction(
        context: ExecutionContext,
        block: (Session) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            factory.fromTransaction { session ->
                initializeSession(session, context)
                block(session).also { coroutine.ensureActive() }
            }
        }

    /** Build a GRT snapshot while [session] is open; no managed values escape into its fields. */
    fun <T : CompositeOutput> project(
        context: ExecutionContext,
        session: Session,
        entity: Any,
        selections: SelectionSet<T>,
    ): T = projection.build(context, HibernateObject(session, entity), selections)

    suspend fun <T> fetchNode(
        context: SelectiveNodeExecutionContext<T>,
    ): T
        where T : CompositeOutput, T : NodeObject =
        transaction(context) { session ->
            val entity =
                requireNotNull(
                    session.find(
                        context.id.type.name,
                        identifier(session, context.id.type.name, context.id.internalID),
                    ),
                ) {
                    "${context.id.type.name} '${context.id.internalID}' was not found"
                }
            projection.node(context, HibernateObject(session, entity))
        }

    /** Contexts must belong to one Viaduct execution. Each keeps its own owned/requested selections. */
    suspend fun <T, C> fetchNodes(
        contexts: List<C>,
    ): Map<C, FieldValue<T>>
        where T : CompositeOutput, T : NodeObject, C : SelectiveNodeExecutionContext<T> {
        if (contexts.isEmpty()) return emptyMap()
        require(contexts.all { it.requestContext === contexts.first().requestContext }) {
            "Batch contexts must belong to the same request"
        }
        return transaction(contexts.first()) { session ->
            contexts
                .groupBy { it.id.type.name }
                .flatMap { (type, group) ->
                    val ids = group.map { identifier(session, type, it.id.internalID) }.distinct()
                    val entities =
                        session
                            .byMultipleIds<Any>(type)
                            .multiLoad(ids)
                            .filterNotNull()
                            .associateBy { session.getIdentifier(it) }
                    group.map { context ->
                        val entity = entities[identifier(session, type, context.id.internalID)]
                        val result =
                            if (entity == null) {
                                FieldValue.ofError(
                                    IllegalStateException("$type '${context.id.internalID}' was not found"),
                                )
                            } else {
                                nodeResult(context, HibernateObject(session, entity))
                            }
                        context to result
                    }
                }.toMap()
        }
    }

    @Suppress("TooGenericExceptionCaught") // FieldValue carries ordinary per-node projection exceptions.
    private fun <T> nodeResult(context: SelectiveNodeExecutionContext<T>, entity: HibernateObject): FieldValue<T>
        where T : CompositeOutput, T : NodeObject =
        try {
            FieldValue.ofValue(projection.node(context, entity))
        } catch (failure: InvocationTargetException) {
            // Generated builders use reflection; unwrap its envelope before classifying the failure.
            val cause = failure.cause ?: failure
            if (cause !is Exception || cause is CancellationException) throw cause
            FieldValue.ofError(cause)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FieldValue.ofError(failure)
        }

    /**
     * Supply an unpaged Hibernate entity query ordered deterministically (include the identifier
     * as a tie-breaker). Use HQL or Criteria for root queries and relationship queries alike.
     * Viaduct alone computes bounds/cursors/PageInfo; Hibernate supplies a count and slice.
     */
    suspend fun <R : Connection<*, *>> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        selections: SelectionSet<R>,
        query: (Session) -> SelectionQuery<*>,
    ): R =
        transaction(context) { session ->
            val selection = query(session)
            require(
                selection.firstResult == 0 && selection.maxResults == Int.MAX_VALUE,
            ) { "Connection queries must be unpaged" }
            connections.build(
                context,
                selections,
                count = { Math.toIntExact(selection.resultCount) },
                slice = { offset, limit ->
                    selection
                        .setFirstResult(
                            offset,
                        ).setMaxResults(limit)
                        .resultList
                        .map { HibernateObject(session, requireNotNull(it)) }
                },
            )
        }
}
