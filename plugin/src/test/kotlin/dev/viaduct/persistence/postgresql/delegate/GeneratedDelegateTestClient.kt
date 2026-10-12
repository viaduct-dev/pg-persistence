@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.orm.grt.GrtBinding
import dev.viaduct.persistence.orm.grt.GrtBindings
import dev.viaduct.persistence.orm.grt.GrtEntity
import dev.viaduct.persistence.orm.grt.GrtReads
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.query.SelectionQuery
import org.hibernate.query.criteria.JpaCriteriaQuery
import viaduct.api.FieldValue
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query

/** Test-only IO/initialization wiring. Production callers use native Session/transaction APIs. */
class GeneratedDelegateTestClient(
    private val factory: SessionFactory,
    private val bindings: GrtBindings,
    private val initialize: (Session, ResolverExecutionContext<out Query>) -> Unit,
) {
    val reads = GrtReads(bindings)

    suspend fun <R> transaction(
        context: ResolverExecutionContext<out Query>,
        block: (Session) -> R,
    ): R =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            factory.withOptions().interceptor(bindings.interceptor(context)).openSession().use { session ->
                session.fromTransaction { transaction ->
                    runCatching {
                        initialize(session, context)
                        block(session).also { coroutine.ensureActive() }
                    }.onFailure { failure ->
                        // Hibernate 7.3's helper does not roll back Errors. Guard inside its callback.
                        if (transaction.isActive) {
                            runCatching { transaction.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
                        }
                    }.getOrThrow()
                }
            }
        }

    suspend fun <T : NodeObject> fetchNode(context: SelectiveNodeExecutionContext<T>): T =
        transaction(context) { reads.fetchNode(context, it) }

    suspend fun <T, C> fetchNodes(
        contexts: List<C>,
    ): Map<C, FieldValue<T>>
        where T : NodeObject, C : SelectiveNodeExecutionContext<T> =
        if (contexts.isEmpty()) emptyMap() else transaction(contexts.first()) { reads.fetchNodes(contexts, it) }

    suspend fun <R : Connection<*, *>, T : Object> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        selections: SelectionSet<R>,
        rowBinding: GrtBinding<T>,
        query: (Session, JpaCriteriaQuery<Tuple>, Root<out GrtEntity<T>>) -> SelectionQuery<Tuple>,
    ): R = transaction(context) { reads.fetchConnection(context, it, selections, rowBinding, query) }

    suspend fun <R : Connection<*, *>> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        selections: SelectionSet<R>,
        query: (Session) -> SelectionQuery<*>,
    ): R = transaction(context) { reads.fetchConnection(context, it, selections, query) }

    fun <T : Object> read(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        binding: GrtBinding<T>,
        selections: SelectionSet<T>,
        query: (JpaCriteriaQuery<Tuple>, Root<out GrtEntity<T>>) -> SelectionQuery<Tuple>,
    ): List<T> = reads.read(context, session, binding, selections, query)

    fun <T : Object> project(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        entity: GrtEntity<T>,
        selections: SelectionSet<T>,
    ): T = entity.project(context, session, selections)
}
