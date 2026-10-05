@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

import dev.dbos.transact.workflow.Workflow
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbTransactionScope
import dev.viaduct.persistence.runtime.db.PgGraphqlDelete
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.PgGraphqlUpdate
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import graphql.Scalars.GraphQLString
import graphql.schema.GraphQLInputObjectType
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.Input
import viaduct.api.types.NodeObject
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

interface MemberWorkflows {
    fun conflictingUpdate(
        first: String,
        second: String,
        audit: String,
        beforeUpdate: Boolean,
    ): Boolean

    fun insert(
        id: String,
        name: String,
    ): String

    fun insertMembersInTransaction(
        first: String,
        second: String,
    ): String

    fun immediate(id: String): String

    fun caughtFailure(id: String): Boolean

    fun applicationFailure(id: String): Boolean

    fun batch(
        first: String,
        second: String,
    ): String

    fun escaped(id: String): Boolean

    fun unsupportedValue(id: String): Boolean

    fun nullValue(id: String): Boolean

    fun nested(id: String): Boolean
}

class MemberWorkflowsImpl(
    private val client: DbClient,
    private val database: DbosTestDatabase,
    private val connection: AtomicReference<Connection>,
) : MemberWorkflows {
    private val context = mockk<ExecutionContext>()
    val calls = AtomicInteger()
    val failAfterCommit = AtomicBoolean()
    var conflictBarrier: java.util.concurrent.CyclicBarrier? = null
    val gateArrivals = AtomicInteger()
    val escapedScope = AtomicReference<DbTransactionScope>()

    @Workflow
    override fun conflictingUpdate(
        first: String,
        second: String,
        audit: String,
        beforeUpdate: Boolean,
    ): Boolean =
        runBlocking {
            client
                .transaction(context) {
                    calls.incrementAndGet()
                    val entity = entity<DbosMember>()
                    entity.insert(MemberInput(audit, "Audit").toPgGraphqlInsert())

                    fun rendezvous() {
                        if (gateArrivals.getAndIncrement() < 2) {
                            requireNotNull(conflictBarrier).await(10, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    }
                    if (beforeUpdate) rendezvous()
                    entity.update(PgGraphqlUpdate(PgGraphqlObject.of("name" to "Updated"), PgGraphqlFilter.eq("uuidId", first)))
                    if (!beforeUpdate) rendezvous()
                    entity.update(PgGraphqlUpdate(PgGraphqlObject.of("name" to "Updated"), PgGraphqlFilter.eq("uuidId", second)))
                    true
                }.value
        }

    @Workflow
    override fun insert(
        id: String,
        name: String,
    ): String =
        runBlocking {
            val committed =
                client.transaction(context) {
                    calls.incrementAndGet()
                    entity<DbosMember>().insert(MemberInput(id, name).toPgGraphqlInsert())
                }
            check(!failAfterCommit.getAndSet(false)) { "After commit" }
            requireNotNull(committed.result[committed.value]).toString()
        }

    @Workflow
    override fun insertMembersInTransaction(
        first: String,
        second: String,
    ): String =
        runBlocking {
            val committed =
                client.transaction(context) {
                    listOf(
                        entity<DbosMember>().insert(MemberInput(first, "First").toPgGraphqlInsert()),
                        entity<DbosMember>().insert(MemberInput(second, "Second").toPgGraphqlInsert()),
                    )
                }
            committed.value.map { requireNotNull(committed.result[it]) }.toString()
        }

    @Workflow
    override fun immediate(id: String): String =
        runBlocking {
            client
                .transaction(context) {
                    entity<DbosMember>().insert(MemberInput(id, "Visible inside").toPgGraphqlInsert())
                    // A separate connection must not see it before DBOS commits.
                    check(database.name(id) == null)
                    connection.get().prepareStatement("SELECT name FROM member WHERE uuid_id = ?::uuid").use { statement ->
                        statement.setString(1, id)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "Insert was buffered instead of executed" }
                            rows.getString(1)
                        }
                    }
                }.value
        }

    @Workflow
    override fun caughtFailure(id: String): Boolean =
        runBlocking {
            client
                .transaction(context) {
                    entity<DbosMember>().insert(MemberInput(id, "First").toPgGraphqlInsert())
                    runCatching { entity<DbosMember>().insert(MemberInput(id, "Duplicate").toPgGraphqlInsert()) }
                    true
                }.value
        }

    @Workflow
    override fun applicationFailure(id: String): Boolean =
        runBlocking {
            client
                .transaction(context) {
                    entity<DbosMember>().insert(MemberInput(id, "Not committed").toPgGraphqlInsert())
                    throw IllegalArgumentException("Application rejected the change")
                }.value
        }

    @Workflow
    override fun batch(
        first: String,
        second: String,
    ): String =
        runBlocking {
            val committed =
                client.transaction(context) {
                    val entity = entity<DbosMember>()
                    val inserted = entity.insertBatch(listOf(first, second).map { MemberInput(it, "Original").toPgGraphqlInsert() })
                    val updated =
                        entity.updateBatch(
                            listOf(first, second).map {
                                PgGraphqlUpdate(PgGraphqlObject.of("name" to "Updated"), PgGraphqlFilter.eq("uuidId", it))
                            },
                        )
                    val deleted = entity.deleteBatch(listOf(first, second).map { PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", it)) })
                    listOf(inserted) + updated + deleted
                }
            committed.value
                .map {
                    committed.result[it]!!
                        .getValue("affectedCount")
                        .jsonPrimitive.int
                }.joinToString()
        }

    @Workflow
    override fun escaped(id: String): Boolean =
        runBlocking {
            val committed =
                client.transaction(context) {
                    escapedScope.set(this)
                    entity<DbosMember>().insert(MemberInput(id, "Committed").toPgGraphqlInsert())
                    Unit
                }
            committed.value == Unit
        }

    @Workflow
    override fun unsupportedValue(id: String): Boolean =
        runBlocking {
            client.transaction(context) {
                entity<DbosMember>().insert(MemberInput(id, "Not committed").toPgGraphqlInsert())
                UnsupportedValue("Cannot reconstruct this constructor")
            }
            true
        }

    @Workflow
    override fun nullValue(id: String): Boolean =
        runBlocking {
            client
                .transaction(context) {
                    entity<DbosMember>().insert(MemberInput(id, "Committed").toPgGraphqlInsert())
                    null
                }.value == null
        }

    @Workflow
    override fun nested(id: String): Boolean =
        runBlocking {
            client
                .transaction(context) {
                    entity<DbosMember>().insert(MemberInput(id, "Not committed").toPgGraphqlInsert())
                    runBlocking { client.transaction(context) { true } }
                    true
                }.value
        }
}

/** Minimal fixtures exposing the same reflection and input accessors as generated GRTs. */
class UnsupportedValue(
    val value: String,
)

class DbosMember : NodeObject {
    object Reflection : Type<DbosMember> {
        override val name = "DbosMember"
        override val kcls = DbosMember::class
    }
}

class MemberInput(
    id: String,
    name: String,
) : Input {
    val inputData = mapOf("uuidId" to id, "name" to name)
    val graphQLInputObjectType: GraphQLInputObjectType =
        GraphQLInputObjectType
            .newInputObject()
            .name("AddMemberInput")
            .field { it.name("uuidId").type(GraphQLString) }
            .field { it.name("name").type(GraphQLString) }
            .build()
}
