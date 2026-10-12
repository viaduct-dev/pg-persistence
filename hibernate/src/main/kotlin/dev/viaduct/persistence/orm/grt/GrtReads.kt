@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import jakarta.persistence.Tuple
import jakarta.persistence.criteria.Root
import kotlinx.coroutines.CancellationException
import org.hibernate.Session
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

/**
 * Detached GRT results from the caller's Hibernate Session. Hibernate owns persistence and
 * transactions; this adapter only applies GraphQL selections and Viaduct connection semantics.
 * Configure trusted database identity and confine the Session to one execution before calling.
 */
class GrtReads(
    private val bindings: GrtBindings,
) {
    fun <T : NodeObject> select(
        context: SelectiveNodeExecutionContext<T>,
        session: Session,
        entity: NodeGrtEntity<T>,
    ): T {
        require(session.contains(entity)) { "Select a managed entity before its session closes" }
        val binding = entity.grtBinding()
        require(binding.type == context.id.type && binding.idType.equals(entity.internalId, context.id)) {
            "Entity does not match the requested node"
        }
        entity.checkContext(context)
        return entity.selected(context, session, selectedFields(binding, context), context.selections())
    }

    fun <T : NodeObject> fetchNode(
        context: SelectiveNodeExecutionContext<T>,
        session: Session,
    ): T {
        val binding = bindings.forType(context.id.type) as NodeGrtBinding<T>
        val reader = requireNotNull(binding.reader) { "No generated read projection for ${binding.entityName}" }
        val fields = selectedFields(binding, context)
        val id = binding.checkedIdentity(context.id)
        val row =
            requireNotNull(reader.rows(session, listOf(id), fields)[id]) {
                "${binding.entityName} '$id' was not found"
            }
        return reader.value(context, session, row, fields, context.selections())
    }

    /** Compatible parent projections share SQL; each context retains its own nested selections. */
    fun <T, C> fetchNodes(
        contexts: List<C>,
        session: Session,
    ): Map<C, FieldValue<T>> where T : NodeObject, C : SelectiveNodeExecutionContext<T> {
        if (contexts.isEmpty()) return emptyMap()
        require(contexts.all { it.requestContext === contexts.first().requestContext }) {
            "Batch contexts must belong to the same execution"
        }
        return contexts
            .groupBy { it.id.type }
            .flatMap { (type, group) ->
                val binding = bindings.forType(type) as NodeGrtBinding<T>
                val reader =
                    requireNotNull(binding.reader) {
                        "No generated read projection for ${binding.entityName}"
                    }
                group.groupBy { selectedFields(binding, it) }.flatMap { (fields, compatible) ->
                    val ids = compatible.map { binding.checkedIdentity(it.id) }
                    val rows = reader.rows(session, ids, fields)
                    compatible.map { context ->
                        context to
                            delegateNodeResult {
                                val row =
                                    requireNotNull(rows[binding.checkedIdentity(context.id)]) {
                                        "${type.name} '${context.id.internalID}' was not found"
                                    }
                                reader.value(context, session, row, fields, context.selections())
                            }
                    }
                }
            }.toMap()
    }

    /** Keep native Criteria predicates/order while projecting only selected mapped columns. */
    fun <T : Object> read(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        binding: GrtBinding<T>,
        selections: SelectionSet<T>,
        query: (JpaCriteriaQuery<Tuple>, Root<out GrtEntity<T>>) -> SelectionQuery<Tuple>,
    ): List<T> {
        require(bindings.byName(binding.entityName) === binding) { "Use this adapter's generated binding" }
        require(selections.type.kcls == binding.type.kcls) { "Selections must match the read type" }
        val reader = requireNotNull(binding.reader) { "No generated read projection for ${binding.entityName}" }
        val fields = reader.fields(binding, selections)
        return reader.query(session, binding.entityClass, fields, query).resultList.map { row ->
            reader.value(context, session, row, fields, selections)
        }
    }

    fun <R : Connection<*, *>, T : Object> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        session: Session,
        selections: SelectionSet<R>,
        rowBinding: GrtBinding<T>,
        query: (Session, JpaCriteriaQuery<Tuple>, Root<out GrtEntity<T>>) -> SelectionQuery<Tuple>,
    ): R {
        context.arguments.validate()
        require(bindings.byName(rowBinding.entityName) === rowBinding) { "Use this adapter's generated binding" }
        return bindings.connection(selections.type).fetch(context, session, rowBinding, selections, query)
    }

    /** Native entity/identifier queries retain normal Hibernate hydration behavior. */
    fun <R : Connection<*, *>> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        session: Session,
        selections: SelectionSet<R>,
        query: (Session) -> SelectionQuery<*>,
    ): R {
        context.arguments.validate()
        val selection = query(session)
        val bounds = connectionBounds(context, selection)
        val rows = selection.setFirstResult(bounds.offset).setMaxResults(Math.addExact(bounds.limit, 1)).resultList
        return bindings.connection(selections.type).build(context, session, rows, bounds, selections)
    }
}

internal fun connectionBounds(
    context: ConnectionFieldExecutionContext<*, *, *, *>,
    selection: SelectionQuery<*>,
): viaduct.api.types.OffsetLimit {
    require(
        selection.firstResult == 0 && selection.maxResults == Int.MAX_VALUE,
    ) { "Connection queries must be unpaged" }
    return if (context.arguments.requiresTotalCountForOffsetLimit()) {
        context.arguments.toOffsetLimit(totalCount = Math.toIntExact(selection.resultCount))
    } else {
        context.arguments.toOffsetLimit()
    }
}

private fun <T : NodeObject> selectedFields(
    binding: GrtBinding<T>,
    context: SelectiveNodeExecutionContext<T>,
): Set<String> {
    val owned = context.ownedSelections()
    val requested = context.selections()
    return binding.fields
        .filter { owned.contains(it) || it.name in binding.references && requested.contains(it) }
        .map { it.name }
        .toSet()
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
