package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immediate transaction inherited by composed resolvers in the current coroutine. */
internal class ActiveImmediateTransaction(
    val owner: DbClient,
    val transport: PgGraphqlTransport,
    val scope: DbTransactionScope,
) : AbstractCoroutineContextElement(ActiveImmediateTransaction) {
    companion object Key : CoroutineContext.Key<ActiveImmediateTransaction>
}
