@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.DelegateFixture.Companion.withFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hibernate.Hibernate
import org.hibernate.LockMode
import org.hibernate.Session
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineObjectData
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Native collection loading/tracking, with detached GRT snapshots and real Viaduct execution. */
class HibernateGrtCollectionIntegrationTest {
    @Test
    fun `unselected collections stay lazy during hydration snapshot reads and flush`() =
        withFixture { f ->
            val ids = seed(f)
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val result =
                f.transaction { session ->
                    val group = f.findEntity(session, ids.group) as DelegateGroup
                    val snapshot = group.selected(setOf("name"))
                    session.flush()
                    listOf(
                        snapshot.get<String>("name", String::class),
                        Hibernate.isInitialized(group.members),
                        Hibernate.isInitialized(group.users),
                        Hibernate.isInitialized(group.featuredUsers),
                        f.factory.statistics.collectionFetchCount,
                        f.factory.statistics.entityLoadCount,
                        f.factory.statistics.entityUpdateCount,
                    )
                }
            assertEquals(listOf("team", false, false, false, 0L, 1L, 0L), result)
        }

    @ParameterizedTest
    @ValueSource(strings = ["members", "users", "featuredUsers"])
    fun `native FK and join collection loading yields detached references with hidden child scalars`(field: String) =
        withFixture { f ->
            val ids = seed(f)
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val snapshot = f.fetch(f.nodeContext(ids.group, "name $field { id }"))
            val raw = (snapshot.__engineObject as EngineObjectData.Sync).get(field) as List<*>
            val refs = snapshot.get<List<ObjectBase>>(field, List::class)
            val expected = if (field == "members") ids.member else ids.person
            val mutation = runCatching { (raw as MutableList<*>).clear() }.exceptionOrNull()
            assertEquals(
                listOf(expected.internalID, true, true, 2L, 1L),
                listOf(
                    f.id(refs.single()).internalID,
                    runCatching {
                        refs.single().get<String>(if (field == "members") "role" else "username", String::class)
                    }.isFailure,
                    mutation is UnsupportedOperationException,
                    f.factory.statistics.entityLoadCount,
                    f.factory.statistics.collectionFetchCount,
                ),
            )
        }

    @Test
    fun `initial GRT collections are rejected instead of silently losing association writes`() =
        withFixture { f ->
            val binding = f.binding(target = f.types.getValue("group"))
            val entity = f.group("original")
            val supplied =
                binding
                    .builder()
                    .put("name", "replacement")
                    .put("users", emptyList<NodeObject>())
                    .build() as ObjectBase
            val failure = runCatching { entity.initialize(supplied) }.exceptionOrNull()
            assertEquals(
                listOf(true, "original", 0),
                listOf(failure is IllegalArgumentException, entity.name, entity.users.size),
            )
        }

    @Test
    fun `initial single valued GRT association is also rejected before changing managed state`() =
        withFixture { f ->
            val manager = f.insert("manager")
            val entity = DelegatePerson(f.binding()).also { it.initialize(f.person("original")) }
            val supplied =
                GrtDelegate
                    .toBuilder(f.person("replacement"))
                    .put("manager", f.context.ref(manager))
                    .build() as ObjectBase
            val failure = runCatching { entity.initialize(supplied) }.exceptionOrNull()
            assertEquals(
                listOf(true, "original", null),
                listOf(failure is IllegalArgumentException, entity.username, entity.manager),
            )
        }

    @Test
    fun `requested collection outside owned selections returns refs without exposing scalars`() =
        withFixture { f ->
            val ids = seed(f)
            val base = f.nodeContext(ids.group, "name members { role person { username } }")
            val context =
                object : SelectiveNodeExecutionContext<NodeObject> by base, InternalContext by f.internal {
                    override fun ownedSelections() = f.nodeContext(ids.group, "name").ownedSelections()
                }
            val group = f.fetch(context)
            val member = group.get<List<ObjectBase>>("members", List::class).single()
            assertEquals(
                listOf("team", ids.member.internalID, true, true),
                listOf(
                    group.get<String>("name", String::class),
                    f.id(member).internalID,
                    runCatching { member.get<String>("role", String::class) }.isFailure,
                    !(group.__engineObject as EngineObjectData.Sync).isPresent("users"),
                ),
            )
        }

    @Test
    fun `join collection dirty checking preserves old snapshots and omitted collections`() =
        withFixture { f ->
            val ids = seed(f)
            val second = f.insert("bob")
            val snapshots =
                f.transaction { session ->
                    val group = f.findEntity(session, ids.group) as DelegateGroup
                    val before = group.selected(setOf("users")) // Leave its generated getter cache unread.
                    group.users.add(f.find(session, second))
                    val after = group.selected(setOf("users"))
                    group.users.removeAt(0)
                    listOf(before, after)
                }
            val fresh = f.fetch(f.nodeContext(ids.group, "users { id } featuredUsers { id }"))
            assertEquals(
                listOf(
                    listOf(ids.person.internalID),
                    listOf(ids.person.internalID, second.internalID).sorted(),
                    listOf(second.internalID),
                    listOf(ids.person.internalID),
                ),
                listOf(
                    refIds(f, snapshots[0], "users"),
                    refIds(f, snapshots[1], "users"),
                    refIds(f, fresh, "users"),
                    refIds(f, fresh, "featuredUsers"),
                ),
            )
        }

    @Test
    fun `membership owning FK and role updates reload through native inverse collection`() =
        withFixture { f ->
            val ids = seed(f)
            val other =
                f.transaction { session ->
                    val group = f.group("other")
                    session.persist(f.types.getValue("group").name, group)
                    f.id(group.value)
                }
            f.transaction { session ->
                val member = f.findEntity(session, ids.member) as DelegateMembership
                member.groupId = f.findEntity(session, other) as DelegateGroup
                member.role = "ADMIN"
            }
            val old = f.fetch(f.nodeContext(ids.group, "members { id }"))
            val moved = f.fetch(f.nodeContext(other, "members { id }"))
            val member = f.fetch(f.nodeContext(ids.member, "role groupId"))
            assertEquals(
                listOf(emptyList<String>(), listOf(ids.member.internalID), "ADMIN", other.internalID),
                listOf(
                    refIds(f, old, "members"),
                    refIds(f, moved, "members"),
                    member.get<String>("role", String::class),
                    member.get<GlobalID<*>>("groupId", GlobalID::class).internalID,
                ),
            )
        }

    @Test
    fun `inverse collection updates use the owning association and can traverse a cycle`() =
        withFixture { f ->
            val first = f.insert("alice")
            val second = f.insert("bob")
            f.transaction { session ->
                val alice = f.find(session, first)
                val bob = f.find(session, second)
                alice.manager = bob
                bob.manager = alice
            }
            val expected =
                mapOf(
                    "username" to "alice",
                    "reports" to listOf(mapOf("username" to "bob", "manager" to mapOf("username" to "alice"))),
                )
            assertEquals(
                listOf(mapOf("person" to expected), emptyList<Any>()),
                f.execute(first, "{ person { username reports { username manager { username } } } }"),
            )
        }

    @Test
    fun `native removal deletes membership rows and collection reads omit them`() =
        withFixture { f ->
            val ids = seed(f)
            f.transaction { session -> session.remove(f.findEntity(session, ids.member)) }
            val snapshot = f.fetch(f.nodeContext(ids.group, "members { id } users { id }"))
            assertEquals(
                listOf(emptyList<String>(), listOf(ids.person.internalID)),
                listOf(refIds(f, snapshot, "members"), refIds(f, snapshot, "users")),
            )
        }

    @Test
    fun `native join collection update rolls back on failure without hanging`() =
        withFixture { f ->
            val ids = seed(f)
            val failed =
                withTimeout(10000) {
                    runCatching {
                        f.transaction { session ->
                            val group = f.findEntity(session, ids.group) as DelegateGroup
                            group.users.clear()
                            session.flush()
                            error("abort")
                        }
                    }.isFailure
                }
            val snapshot = f.fetch(f.nodeContext(ids.group, "users { id }"))
            assertEquals(listOf(true, listOf(ids.person.internalID)), listOf(failed, refIds(f, snapshot, "users")))
        }

    @Test
    fun `parallel sessions return independent detached collection snapshots`() =
        withFixture { f ->
            val ids = seed(f)
            val snapshots =
                withTimeout(10000) {
                    supervisorScope {
                        List(4) {
                            async { f.fetch(f.nodeContext(ids.group, "members { id } users { id }")) }
                        }.awaitAll()
                    }
                }
            assertEquals(
                List(4) {
                    listOf(listOf(ids.member.internalID), listOf(ids.person.internalID))
                },
                snapshots.map { listOf(refIds(f, it, "members"), refIds(f, it, "users")) },
            )
        }

    @Test
    fun `contended relationship transaction times out rolls back and releases its session`() =
        withFixture { f ->
            val ids = seed(f)
            val failure =
                withTimeout(10000) {
                    supervisorScope {
                        val locked = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val holder =
                            async {
                                f.transaction { session ->
                                    lockedGroup(session, ids.group)
                                    locked.countDown()
                                    check(release.await(5, TimeUnit.SECONDS)) {
                                        "Test did not release its database lock"
                                    }
                                }
                            }
                        try {
                            withContext(Dispatchers.IO) { check(locked.await(5, TimeUnit.SECONDS)) }
                            runCatching {
                                f.transaction { session ->
                                    session
                                        .createNativeMutationQuery("SET LOCAL lock_timeout = '250ms'")
                                        .executeUpdate()
                                    val group = lockedGroup(session, ids.group)
                                    group.users.clear()
                                }
                            }.exceptionOrNull()
                        } finally {
                            release.countDown()
                            holder.await()
                        }
                    }
                }
            f.transaction { session -> (f.findEntity(session, ids.group) as DelegateGroup).name = "after" }
            val snapshot = f.fetch(f.nodeContext(ids.group, "name users { id }"))
            assertEquals(
                listOf(true, "after", listOf(ids.person.internalID)),
                listOf(
                    generateSequence(failure) { it.cause }.any { "lock timeout" in it.message.orEmpty() },
                    snapshot.get<String>("name", String::class),
                    refIds(f, snapshot, "users"),
                ),
            )
        }

    @Test
    fun `real GraphQL resolves membership roles users aliases and repeated collection selections`() =
        withFixture { f ->
            val ids = seed(f)
            val member = mapOf("access" to "MEMBER", "person" to mapOf("label" to "alice"))
            val group =
                mapOf(
                    "name" to "team",
                    "first" to listOf(member),
                    "second" to listOf(mapOf("role" to "MEMBER")),
                    "users" to listOf(mapOf("username" to "alice")),
                )
            assertEquals(
                listOf(mapOf("group" to group, "healthy" to "ok"), emptyList<Any>()),
                f.execute(
                    ids.group,
                    "{ group { name first: members { access: role person { label: username } } " +
                        "second: members { role } users { username } } healthy }",
                ),
            )
        }

    @Test
    fun `checker denial bubbles through collection items with alias and index paths`() =
        withFixture { f ->
            val ids = seed(f)
            assertEquals(
                listOf(
                    mapOf("subject" to null, "healthy" to "ok"),
                    listOf(listOf("subject", "members", 0, "person", "label")),
                ),
                f.execute(
                    ids.group,
                    "{ subject: group { members { person { label: username } } } healthy }",
                    denied = true,
                ),
            )
        }

    @Test
    fun `collection view toBuilder does not expose omitted scalar or collection fields`() =
        withFixture { f ->
            val ids = seed(f)
            val snapshot = f.fetch(f.nodeContext(ids.group, "members { id }"))
            val edited = GrtDelegate.toBuilder(snapshot).build() as ObjectBase
            val data = edited.__engineObject as EngineObjectData.Sync
            assertEquals(
                listOf(listOf(ids.member.internalID), false, false),
                listOf(refIds(f, edited, "members"), data.isPresent("name"), data.isPresent("users")),
            )
        }

    private data class Seed(
        val person: GlobalID<NodeObject>,
        val group: GlobalID<NodeObject>,
        val member: GlobalID<NodeObject>,
    )

    private fun lockedGroup(
        session: Session,
        id: GlobalID<NodeObject>,
    ): DelegateGroup =
        session
            .createSelectionQuery("from ${id.type.name} where internalId = :id", DelegateGroup::class.java)
            .setParameter("id", UUID.fromString(id.internalID))
            .setHibernateLockMode(LockMode.PESSIMISTIC_WRITE)
            .singleResult

    private suspend fun seed(f: DelegateFixture): Seed {
        val personId = f.insert("alice")
        return f.transaction { session ->
            val person = f.find(session, personId)
            val group = f.group("team")
            group.users.add(person)
            group.featuredUsers.add(person)
            session.persist(f.types.getValue("group").name, group)
            val member = f.membership(person, group)
            session.persist(f.types.getValue("membership").name, member)
            Seed(personId, f.id(group.value), f.id(member.value))
        }
    }

    private fun refIds(
        f: DelegateFixture,
        value: ObjectBase,
        field: String,
    ): List<String> = value.get<List<ObjectBase>>(field, List::class).map { f.id(it).internalID }.sorted()
}
