@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.orm.HibernateClient
import dev.viaduct.persistence.postgresql.HibernateRuntimeFixture.Companion.withFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.hibernate.Session
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Native Hibernate behavior using schema-generated mappings and unchanged Viaduct codegen. */
class HibernateNativeIntegrationTest {
    @Test
    fun `native inserts need neither the pg_graphql overlay nor an assigned UUID`() =
        withFixture(pgGraphql = false) { f ->
            val id = f.insert("alice")
            assertEquals(listOf("alice", "initial"), values(f.orm(id, "username nickname")))
        }

    @Test
    fun `selected GRT values match the existing provider`() =
        withFixture { f ->
            val id = f.insert("alice")
            assertEquals(values(f.pg(id, "username nickname")), values(f.orm(id, "username nickname")))
        }

    @Test
    fun `typed input omissions and explicit null use ordinary Hibernate dirty checking`() =
        withFixture { f ->
            val id = f.insert("alice")
            f.update(id, f.input("username" to "bob"))
            val omitted = values(f.orm(id, "username nickname"))
            f.update(id, f.input("nickname" to null))
            assertEquals(
                listOf(listOf("bob", "initial"), listOf("bob", null)),
                listOf(omitted, values(f.orm(id, "username nickname"))),
            )
        }

    @Test
    fun `native associations become detached Viaduct references`() =
        withFixture { f ->
            val manager = f.insert("manager")
            val person = f.insert("alice")
            f.update(person, f.input("manager" to manager))
            val node = f.orm(person, "manager { id }")
            assertEquals(manager.internalID, nodeId(node.get("manager", NodeObject::class)))
        }

    @Test
    fun `batch reads keep each contexts selections and a missing row error`() =
        withFixture { f ->
            val id = f.insert("alice")
            val contexts =
                listOf(
                    f.nodeContext(id, "username"),
                    f.nodeContext(id, "nickname"),
                    f.nodeContext(f.id(), "username"),
                )
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val results = f.client.fetchNodes(contexts)
            assertEquals(
                listOf(listOf("alice"), listOf("initial"), true, 1L),
                listOf(
                    values(results.getValue(contexts[0]).get() as ObjectBase),
                    values(results.getValue(contexts[1]).get() as ObjectBase),
                    runCatching { results.getValue(contexts[2]).get() }.isFailure,
                    f.factory.statistics.prepareStatementCount,
                ),
            )
        }

    @Test
    fun `requested relationships outside owned fields retain node references`() =
        withFixture { f ->
            val manager = f.insert("manager")
            val person = f.insert("alice")
            f.update(person, f.input("manager" to manager))
            val base = f.nodeContext(person, "username manager { id }")
            val context =
                object :
                    viaduct.api.context.SelectiveNodeExecutionContext<NodeObject> by base,
                    viaduct.api.internal.InternalContext by f.internal {
                    override fun ownedSelections() = f.selections("username")
                }
            val node = f.client.fetchNode(context) as ObjectBase
            assertEquals(
                listOf("alice", manager.internalID),
                listOf(
                    node.get<String>("username", String::class),
                    nodeId(node.get("manager", NodeObject::class)),
                ),
            )
        }

    @Test
    fun `snapshot getters and omitted fields do not depend on a closed session`() =
        withFixture { f ->
            val id = f.insert("alice")
            val snapshot = f.orm(id, "username")
            f.update(id, f.input("username" to "bob"))
            assertEquals(
                listOf("alice", true),
                listOf(
                    snapshot.get<String>("username", String::class),
                    runCatching { snapshot.get<String>("nickname", String::class) }.isFailure,
                ),
            )
        }

    @ParameterizedTest
    @ValueSource(strings = ["abort", "constraint", "cancel", "fatal"])
    fun `native transactions roll back on failure or cancellation`(case: String) =
        withFixture { f ->
            val fatal = LinkageError("fatal")
            val failure =
                kotlinx.coroutines.supervisorScope {
                    val job =
                        async {
                            val coroutine = currentCoroutineContext()
                            f.client.transaction(f.ctx) { session ->
                                session.persist(f.type().name, mutableMapOf("username" to "alice"))
                                when (case) {
                                    "abort" -> error("abort")
                                    "constraint" -> session.persist(f.type().name, mutableMapOf("username" to "alice"))
                                    "cancel" -> coroutine.cancel()
                                    else -> throw fatal
                                }
                            }
                        }
                    runCatching { job.await() }.exceptionOrNull()
                }
            assertEquals(
                listOf(true, emptyList<String>()),
                listOf(
                    if (case ==
                        "fatal"
                    ) {
                        generateSequence(failure) { it.cause }.any { it === fatal }
                    } else {
                        failure != null
                    },
                    f.lookup("username", "alice"),
                ),
            )
        }

    @Test
    fun `concurrent resolvers get separate sessions and finish without hanging`() =
        withFixture { f ->
            val id = f.insert("alice")
            val results =
                withTimeout(10000) {
                    coroutineScope {
                        List(20) { async { values(f.orm(id, "username")) } }.map { it.await() }
                    }
                }
            assertEquals(List(20) { listOf("alice") }, results)
        }

    @Test
    fun `initialization uses each callers context inside an active transaction`() =
        withFixture { f ->
            val seen = mutableListOf<Boolean>()
            val client =
                HibernateClient(f.factory) { session, context ->
                    seen.add(context === f.ctx && session.transaction.isActive)
                }
            client.transaction(f.ctx) { 1 }
            client.transaction(f.ctx) { 2 }
            assertEquals(listOf(true, true), seen)
        }

    @Test
    fun `membership joins use the generated owning Hibernate association`() =
        withFixture { f ->
            val person = f.insert("alice")
            val groupType = f.type("OrmGroup").name
            val memberType = f.type("OrmMember").name
            val groupId =
                f.client.transaction(f.ctx) { session ->
                    val member =
                        mutableMapOf<String, Any?>(
                            "person" to session.getReference(person.type.name, UUID.fromString(person.internalID)),
                        )
                    val group = mutableMapOf<String, Any?>("name" to "team", "members" to mutableListOf<Any>())
                    session.persist(groupType, group)
                    member["${groupType.replaceFirstChar(Char::lowercaseChar)}Id"] = group
                    session.persist(memberType, member)
                    session.getIdentifier(group)
                }
            val members =
                f.client.transaction(f.ctx) { session ->
                    session
                        .createSelectionQuery(
                            "select m from $groupType g join g.members m " +
                                "where g.internalId = :groupId and m.person.internalId = :personId",
                            Any::class.java,
                        ).setParameter("groupId", groupId)
                        .setParameter("personId", UUID.fromString(person.internalID))
                        .resultList.size
                }
            assertEquals(1, members)
        }

    @Test
    fun `checklist CRUD uses exact mapped idOf names and native Hibernate methods`() =
        withFixture { f ->
            val groupType = f.type("OrmGroup").name
            val checklist = f.type("OrmChecklist")
            val id =
                f.client.transaction(f.ctx) { session ->
                    val group = mutableMapOf<String, Any?>("name" to "team", "members" to mutableListOf<Any>())
                    session.persist(groupType, group)
                    val item = mutableMapOf<String, Any?>("title" to "First", "completed" to false, "groupId" to group)
                    session.persist(checklist.name, item)
                    f.ctx.globalIDFor(checklist, session.getIdentifier(item).toString())
                }
            f.client.transaction(f.ctx) { session -> entity(session, id)["completed"] = true }
            val item = f.orm(id, "title completed groupId")
            f.client.transaction(f.ctx) { session -> session.remove(entity(session, id)) }
            assertEquals(
                listOf("First", true, true, null),
                listOf(
                    item.get<String>("title", String::class),
                    item.get<Boolean>("completed", Boolean::class),
                    item.get<GlobalID<*>>("groupId", GlobalID::class).type.name == groupType,
                    f.client.transaction(f.ctx) { session ->
                        session.find(checklist.name, UUID.fromString(id.internalID))
                    },
                ),
            )
        }

    @Test
    fun `ordinary HQL covers status lists searches and bound keys`() =
        withFixture { f ->
            f.insert("alice' OR 1=1 --")
            f.insert("bob")
            val count =
                f.client.transaction(f.ctx) { session ->
                    session
                        .createSelectionQuery(
                            "select count(p) from ${f.type().name} p where p.nickname is not null " +
                                "and p.username ilike :pattern and p.username in :names",
                            java.lang.Long::class.java,
                        ).setParameter("pattern", "%alice%")
                        .setParameterList("names", listOf("alice' OR 1=1 --"))
                        .singleResult
                        .toLong()
                }
            assertEquals(1L, count)
        }

    @ParameterizedTest
    @ValueSource(strings = ["first", "last", "empty", "after", "before", "large-last", "default"])
    fun `connection pages and PageInfo agree with the existing provider`(case: String) =
        withFixture { f ->
            f.insert("alice")
            f.insert("bob")
            val cursor =
                viaduct.api.types.OffsetCursor
                    .fromOffset(if (case == "empty" || case == "before") 1 else 0)
                    .value
            val args =
                when (case) {
                    "first" -> mapOf("first" to 1)
                    "last" -> mapOf("last" to 1)
                    "empty", "after" -> mapOf("first" to 1, "after" to cursor)
                    "before" -> mapOf("last" to 1, "before" to cursor)
                    "large-last" -> mapOf("last" to 5)
                    else -> emptyMap()
                }
            assertEquals(pageValues(f.page(args, pg = true)), pageValues(f.page(args, pg = false)))
        }

    @Test
    fun `native scalar columns and arrays retain their GRT representations`() =
        withFixture { f ->
            val id = f.insert("alice")
            val time = OffsetDateTime.parse("2026-10-08T08:34:56-04:00")
            f.client.transaction(f.ctx) { session ->
                entity(session, id).putAll(
                    mapOf(
                        "amount" to BigDecimal("12.5"),
                        "happenedAt" to time,
                        "labels" to arrayOf("a", "b"),
                        "status" to "ACTIVE",
                        "statuses" to arrayOf("ACTIVE", null, "INACTIVE"),
                        "happenedTimes" to arrayOf(time, null),
                        "metadata" to mapOf("enabled" to true),
                    ),
                )
            }
            val value = f.orm(id, "amount happenedAt labels status statuses happenedTimes metadata")
            assertEquals(
                listOf(
                    BigDecimal("12.50"),
                    time.toInstant(),
                    listOf("a", "b"),
                    "ACTIVE",
                    listOf("ACTIVE", null, "INACTIVE"),
                    listOf(time.toInstant(), null),
                    mapOf("enabled" to true),
                ),
                listOf(
                    value.get<Any>("amount", Any::class),
                    value.get<Any>("happenedAt", Any::class),
                    value.get<Any>("labels", Any::class),
                    value.get<Any>("status", Any::class).toString(),
                    value.get<List<Any?>>("statuses", Any::class).map { it?.toString() },
                    value.get<Any>("happenedTimes", Any::class),
                    value.get<Any>("metadata", Any::class),
                ),
            )
        }

    @Test
    fun `missing nodes propagate a normal resolver failure`() =
        withFixture { f ->
            assertFailsWith<IllegalArgumentException> { f.orm(f.id(), "username") }
        }

    @Suppress("UNCHECKED_CAST")
    private fun entity(
        session: Session,
        id: GlobalID<*>,
    ): MutableMap<String, Any?> = session.find(id.type.name, UUID.fromString(id.internalID)) as MutableMap<String, Any?>

    private fun values(value: ObjectBase): List<Any?> =
        listOf("username", "nickname")
            .filter { (value.__engineObject as viaduct.engine.api.EngineObjectData.Sync).getSelections().contains(it) }
            .map { value.get<Any?>(it, Any::class) }

    private fun nodeId(value: ObjectBase): String = value.get<GlobalID<*>>("id", GlobalID::class).internalID

    private fun pageValues(value: ObjectBase): List<Any?> {
        val edges = value.get<List<ObjectBase>>("edges", ObjectBase::class)
        val pageInfo = value.get<ObjectBase>("pageInfo", ObjectBase::class)
        return listOf(
            edges.map { it.get<String>("cursor", String::class) to nodeId(it.get("node", NodeObject::class)) },
            listOf("hasNextPage", "hasPreviousPage", "startCursor", "endCursor").map {
                pageInfo.get<Any?>(it, if (it.startsWith("has")) Boolean::class else String::class)
            },
        )
    }
}
