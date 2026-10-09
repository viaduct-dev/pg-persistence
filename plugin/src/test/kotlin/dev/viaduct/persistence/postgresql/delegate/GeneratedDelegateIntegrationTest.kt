@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hibernate.Hibernate
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The production generator/client, real Viaduct GRT bytecode, and PostgreSQL are exercised together. */
class GeneratedDelegateIntegrationTest {
    @Test
    fun `omitting a nullable scalar cannot silently clear its persisted value`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val incomplete =
                f
                    .builder("Person")
                    .put("id", id)
                    .put("username", "bob")
                    .put("manager", null)
                    .put("happenedAt", null)
                    .put(
                        "status",
                        f.loader
                            .loadClass("dev.viaduct.persistence.approvalfixture.DelegateStatus${f.suffix}")
                            .enumConstants
                            .first(),
                    ).build() as ObjectBase
            val rejected =
                f.client.transaction(f.context) { session ->
                    runCatching { f.access.update(f.client, f.context, session, incomplete) }.isFailure
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(id, "username nickname"))
            assertEquals(
                listOf(true, "alice", "initial"),
                listOf(rejected, get(loaded, "Username"), get(loaded, "Nickname")),
            )
        }

    @Test
    fun `node relationship without identity cannot silently clear persisted state`() =
        withFixture { f ->
            val manager = f.insert(f.person("manager"))
            val id = f.id(f.insert(f.person("alice", manager)))
            val result =
                f.client.transaction(f.context) { session ->
                    val current = f.access.find(f.client, f.context, session, id)
                    val replacement =
                        GrtDelegate
                            .toBuilder(current)
                            .put("manager", f.person("unsaved"))
                            .put("managerId", null)
                            .build() as ObjectBase
                    val rejected = runCatching { f.access.update(f.client, f.context, session, replacement) }.isFailure
                    val unchanged = f.access.find(f.client, f.context, session, id)
                    listOf<Any?>(rejected, unchanged.get("managerId", GlobalID::class))
                }
            val stored = f.access.fetch(f.client, f.nodeContext(id, "managerId"))
            assertEquals(
                listOf(true, f.id(manager), f.id(manager)),
                result + listOf<Any?>(stored.get("managerId", GlobalID::class)),
            )
        }

    @Test
    fun `conflicting paired ids fail before replacing managed state`() =
        withFixture { f ->
            val manager = f.insert(f.person("manager"))
            val other = f.insert(f.person("other"))
            val id = f.id(f.insert(f.person("alice", manager)))
            val rejected =
                f.client.transaction(f.context) { session ->
                    val snapshot = f.access.find(f.client, f.context, session, id)
                    val conflicts =
                        listOf(f.id(other), null).map { supplied ->
                            val value =
                                GrtDelegate
                                    .toBuilder(snapshot)
                                    .put("username", "bob")
                                    .put("managerId", supplied)
                                    .build() as ObjectBase
                            runCatching { f.access.update(f.client, f.context, session, value) }.isFailure
                        }
                    conflicts
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(id, "username managerId"))
            assertEquals(
                listOf(true, true, "alice", f.id(manager)),
                rejected + listOf(get(loaded, "Username"), loaded.get("managerId", GlobalID::class)),
            )
        }

    @Test
    fun `transaction ownership rejects other clients and request contexts on the same factory`() =
        withFixture { f ->
            val another = f.newClient { _, _ -> }
            val context =
                object : ResolverExecutionContext<Query> by f.context {
                    override val requestContext: Any = Any()
                }
            val rejected =
                f.client.transaction(f.context) { session ->
                    listOf(
                        runCatching {
                            f.access.insert(another, f.context, session, f.person("other-client"))
                        }.isFailure,
                        runCatching {
                            f.access.insert(f.client, context, session, f.person("other-request"))
                        }.isFailure,
                    )
                }
            assertEquals(listOf(true, true), rejected)
        }

    @Test
    fun `connection accepts native rows already present as proxies in the session`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val result = f.connection(mapOf("first" to 1), warmProxy = true)
            assertEquals(listOf(listOf(id), false, false), page(f, result))
        }

    @Test
    fun `unidirectional collection uses the synthesized native owning property`() =
        withFixture { f ->
            val parent = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val input = f.builder("Item").put("title", "task").build() as ObjectBase
            val child =
                f.client.transaction(f.context) { session ->
                    val entityName = input.javaClass.name.replace(".Delegate", ".persistence.Delegate") + "Entity"
                    val type = f.loader.loadClass(entityName)
                    val companion = type.getField("Companion").get(null)
                    val binding =
                        companion.javaClass.getMethod("getBINDING").invoke(companion)
                            as dev.viaduct.persistence.orm.grt.GrtBinding<*>
                    val item = binding.create(f.context)
                    type
                        .getMethod("assign", input.javaClass, org.hibernate.Session::class.java)
                        .invoke(item, input, session)
                    val group = f.native(session, f.id(parent))
                    type.getMethod("setDelegateGroup${f.suffix}Id", group.javaClass).invoke(item, group)
                    session.persist("DelegateItem${f.suffix}", item)
                    item.grt() as ObjectBase
                }
            val result = f.access.fetch(f.client, f.nodeContext(f.id(parent), "items { id }"))
            val items = result.get<List<ObjectBase>>("items", child::class)
            assertEquals(listOf(f.id(child)), items.map(f::id))
        }

    @Test
    fun `generated delegates persist unchanged GRTs with generated identity`() =
        withFixture { f ->
            val original = f.person("alice")
            val saved = f.insert(original)
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(saved), "id username status"))
            assertEquals(
                listOf(false, "alice", "ACTIVE", f.id(saved)),
                listOf(
                    data(original).isPresent("id"),
                    get(loaded, "Username"),
                    (get(loaded, "Status") as Enum<*>).name,
                    f.id(loaded),
                ),
            )
        }

    @Test
    fun `dirty checking persists replacement and preserves cached old GRT values`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val later = Instant.parse("2026-10-08T12:34:56Z")
            val old =
                f.client.transaction(f.context) { session ->
                    val snapshot = f.access.find(f.client, f.context, session, id)
                    get(snapshot, "Username")
                    val replacement =
                        GrtDelegate
                            .toBuilder(snapshot)
                            .put("username", "bob")
                            .put("nickname", null)
                            .put("happenedAt", later)
                            .build() as ObjectBase
                    f.access.update(f.client, f.context, session, replacement)
                    snapshot
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(id, "username nickname happenedAt"))
            assertEquals(
                listOf("alice", "initial", null, "bob", null, later),
                listOf(
                    get(old, "Username"),
                    get(old, "Nickname"),
                    get(old, "HappenedAt"),
                    get(loaded, "Username"),
                    get(loaded, "Nickname"),
                    get(loaded, "HappenedAt"),
                ),
            )
        }

    @Test
    fun `unchanged native flush produces no update`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            f.client.transaction(f.context) { session ->
                f.access.find(f.client, f.context, session, id)
                session.flush()
            }
            assertEquals(0L, f.factory.statistics.entityUpdateCount)
        }

    @Test
    fun `partial and invalid replacements leave managed state intact`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val results =
                f.client.transaction(f.context) { session ->
                    val snapshot = f.access.find(f.client, f.context, session, id)
                    val partial =
                        f
                            .builder("Person")
                            .put("id", id)
                            .put("username", "partial")
                            .build() as ObjectBase
                    val invalid =
                        GrtDelegate
                            .toBuilder(snapshot)
                            .put(
                                "id",
                                f.context.globalIDFor(
                                    id.type,
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                ),
                            ).build() as ObjectBase
                    listOf(
                        runCatching { f.access.update(f.client, f.context, session, partial) }.isFailure,
                        runCatching { f.access.update(f.client, f.context, session, invalid) }.isFailure,
                        get(f.access.find(f.client, f.context, session, id), "Username"),
                    )
                }
            assertEquals(listOf(true, true, "alice"), results)
        }

    @Test
    fun `refresh does not mutate published snapshots`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val names =
                f.client.transaction(f.context) { session ->
                    val old = f.access.find(f.client, f.context, session, id)
                    session
                        .createMutationQuery("update ${id.type.name} set username = :name where internalId = :id")
                        .setParameter("name", "bob")
                        .setParameter("id", java.util.UUID.fromString(id.internalID))
                        .executeUpdate()
                    session.refresh(f.native(session, id))
                    listOf(get(old, "Username"), get(f.access.find(f.client, f.context, session, id), "Username"))
                }
            assertEquals(listOf("alice", "bob"), names)
        }

    @Test
    fun `selected views preserve omission without sharing a broader view`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val wide = f.access.fetch(f.client, f.nodeContext(id, "id username nickname"))
            val narrow = f.access.fetch(f.client, f.nodeContext(id, "username"))
            val rebuilt = GrtDelegate.toBuilder(narrow).build() as ObjectBase
            assertEquals(
                listOf(true, false, false, "alice"),
                listOf(
                    data(wide).isPresent("nickname"),
                    data(narrow).isPresent("nickname"),
                    data(rebuilt).isPresent("nickname"),
                    get(narrow, "Username"),
                ),
            )
        }

    @Test
    fun `nullable inverse collection uses the owning key and yields detached references`() =
        withFixture { f ->
            val parent = f.insert(f.person("parent"))
            val child = f.insert(f.person("child", parent))
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(parent), "username reports { id }"))
            val reports = loaded.get<List<ObjectBase>>("reports", child::class)
            assertEquals(listOf(f.id(child)), reports.map(f::id))
        }

    @Test
    fun `membership owning associations preserve exact idOf property names`() =
        withFixture { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val member =
                f.insert(
                    f
                        .builder("Membership")
                        .put("role", "MEMBER")
                        .put("person", person)
                        .put("groupId", f.id(group))
                        .build() as ObjectBase,
                )
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(group), "name members { id }"))
            assertEquals(listOf(f.id(member)), loaded.get<List<ObjectBase>>("members", member::class).map(f::id))
        }

    @Test
    fun `join collections use native persistent bags and publish no lazy wrapper`() =
        withFixture { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val wasLazy =
                f.client.transaction(f.context) { session ->
                    val entity = f.native(session, f.id(group))
                    val bag = entity.javaClass.getMethod("getUsers").invoke(entity)
                    val lazy = !Hibernate.isInitialized(bag)
                    @Suppress("UNCHECKED_CAST")
                    (bag as MutableList<Any>).add(f.native(session, f.id(person)))
                    lazy
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(group), "users { id }"))
            assertEquals(
                listOf(true, listOf(f.id(person))),
                listOf(wasLazy, loaded.get<List<ObjectBase>>("users", person::class).map(f::id)),
            )
        }

    @Test
    fun `native deletion leaves the node missing`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            f.client.transaction(f.context) { f.access.delete(f.client, f.context, it, id) }
            assertEquals(true, runCatching { f.access.fetch(f.client, f.nodeContext(id, "username")) }.isFailure)
        }

    @Test
    fun `fatal failures rollback and preserve the original failure`() =
        withFixture { f ->
            val failure = LinkageError("test")
            val seen =
                runCatching {
                    f.client.transaction(f.context) { session ->
                        f.access.insert(f.client, f.context, session, f.person("rolled-back"))
                        session.flush()
                        throw failure
                    }
                }.exceptionOrNull()
            val saved = f.insert(f.person("rolled-back"))
            assertEquals(
                listOf(true, "rolled-back"),
                listOf(generateSequence(seen) { it.cause }.any { it === failure }, get(saved, "Username")),
            )
        }

    @Test
    fun `cancellation before commit rolls back`() =
        withFixture { f ->
            val cancelled =
                supervisorScope {
                    async {
                        val coroutine = currentCoroutineContext()
                        f.client.transaction(f.context) { session ->
                            f.access.insert(f.client, f.context, session, f.person("rolled-back"))
                            session.flush()
                            coroutine.cancel()
                        }
                    }.let { runCatching { it.await() }.isFailure }
                }
            val saved = f.insert(f.person("rolled-back"))
            assertEquals(listOf(true, "rolled-back"), listOf(cancelled, get(saved, "Username")))
        }

    @Test
    fun `parallel resolver transactions are isolated and complete`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val results =
                withTimeout(10000) {
                    supervisorScope {
                        (1..12)
                            .map {
                                async {
                                    get(
                                        f.access.fetch(f.client, f.nodeContext(id, "username")),
                                        "Username",
                                    )
                                }
                            }.awaitAll()
                    }
                }
            assertEquals(List(12) { "alice" }, results)
        }

    @Test
    fun `batch reads preserve separate ownership and missing row failures`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val narrow = f.nodeContext(id, "username")
            val wide = f.nodeContext(id, "username nickname")
            val missing =
                f.nodeContext(
                    f.context.globalIDFor(
                        id.type,
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ),
                    "username",
                )
            val result = f.access.batch(f.client, listOf(narrow, wide, missing))
            assertEquals(
                listOf(false, true, true),
                listOf(
                    data(result.getValue(narrow).get()).isPresent("nickname"),
                    data(result.getValue(wide).get()).isPresent("nickname"),
                    result.getValue(missing).isError,
                ),
            )
        }

    @Test
    fun `inserts reject sessions opened outside the client transaction`() =
        withFixture { f ->
            val rejected =
                f.factory.openSession().use { session ->
                    session.beginTransaction()
                    try {
                        runCatching { f.access.insert(f.client, f.context, session, f.person("alice")) }.isFailure
                    } finally {
                        session.transaction.rollback()
                    }
                }
            assertEquals(true, rejected)
        }

    @Test
    fun `delegate representations leave physical schema metadata unchanged`() =
        withFixture { f ->
            assertEquals(true, f.schemaUnchanged)
        }

    @Test
    fun `paired idOf fields remain consistent without adding another column`() =
        withFixture { f ->
            val manager = f.insert(f.person("parent"))
            val child = f.insert(f.person("child", manager))
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(child), "managerId manager { id }"))
            assertEquals(
                listOf(f.id(manager), f.id(manager)),
                listOf(
                    loaded.get("managerId", viaduct.api.globalid.GlobalID::class),
                    f.id(loaded.get("manager", manager::class)),
                ),
            )
        }

    @Test
    fun `forward and backward pages use only Viaduct connection cursors`() =
        withFixture { f ->
            val ids = listOf("alice", "bob", "carol").map { f.id(f.insert(f.person(it))) }
            val forward =
                f.connection(
                    mapOf(
                        "first" to 1,
                        "after" to
                            viaduct.api.types.OffsetCursor
                                .fromOffset(0)
                                .value,
                    ),
                )
            val backward = f.connection(mapOf("last" to 1))
            assertEquals(
                listOf(listOf(ids[1]), true, true, listOf(ids[2]), false, true),
                page(f, forward) + page(f, backward),
            )
        }

    @Test
    fun `empty connection has no nodes or page flags`() =
        withFixture { f ->
            assertEquals(listOf(emptyList<Any>(), false, false), page(f, f.connection(emptyMap())))
        }

    @Test
    fun `invalid paging arguments and already paged native queries are rejected`() =
        withFixture { f ->
            val invalid = runCatching { f.connection(mapOf("first" to -1)) }.isFailure
            val prePaged = runCatching { f.connection(emptyMap(), prePaged = true) }.isFailure
            assertEquals(listOf(true, true), listOf(invalid, prePaged))
        }

    @Test
    fun `contended native locks time out rollback and allow the next request`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val failure =
                withTimeout(10000) {
                    supervisorScope {
                        val locked = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val holder =
                            async {
                                f.client.transaction(f.context) { session ->
                                    lock(session, id)
                                    locked.countDown()
                                    check(release.await(5, TimeUnit.SECONDS))
                                }
                            }
                        try {
                            withContext(Dispatchers.IO) { check(locked.await(5, TimeUnit.SECONDS)) }
                            runCatching { f.client.transaction(f.context) { lock(it, id) } }.exceptionOrNull()
                        } finally {
                            release.countDown()
                            holder.await()
                        }
                    }
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(id, "username"))
            assertEquals(
                listOf(true, "alice"),
                listOf(
                    generateSequence(failure) { it.cause }
                        .any { "lock timeout" in it.message.orEmpty() },
                    get(loaded, "Username"),
                ),
            )
        }

    @Test
    fun `request initialization and entity context remain isolated across native sessions`() =
        withFixture { f ->
            val id = f.id(f.insert(f.person("alice")))
            val client =
                f.newClient { session, context ->
                    session
                        .createNativeQuery("select set_config('request.caller', :caller, true)", String::class.java)
                        .setParameter("caller", context.requestContext as String)
                        .singleResult
                }
            val results =
                withTimeout(10000) {
                    supervisorScope {
                        listOf("first", "second")
                            .map { caller ->
                                async {
                                    val context =
                                        object :
                                            viaduct.api.context.ResolverExecutionContext<viaduct.api.types.Query>
                                            by f.context,
                                            viaduct.api.internal.InternalContext by f.internal {
                                            override val requestContext: Any = caller
                                        }
                                    client.transaction(context) { session ->
                                        val name = get(f.access.find(client, context, session, id), "Username")
                                        val setting =
                                            session
                                                .createNativeQuery(
                                                    "select current_setting('request.caller')",
                                                    String::class.java,
                                                ).singleResult
                                        listOf(caller, setting, name)
                                    }
                                }
                            }.awaitAll()
                    }
                }
            assertEquals(listOf(listOf("first", "first", "alice"), listOf("second", "second", "alice")), results)
        }

    private fun lock(
        session: org.hibernate.Session,
        id: viaduct.api.globalid.GlobalID<*>,
    ) {
        session
            .createSelectionQuery("from ${id.type.name} p where p.internalId = :id", Any::class.java)
            .setParameter("id", java.util.UUID.fromString(id.internalID))
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .singleResult
    }

    private fun page(
        f: GeneratedDelegateFixture,
        value: ObjectBase,
    ): List<Any?> {
        val nodes = value.get<List<ObjectBase>>("nodes", f.types.getValue("Person").kcls).map(f::id)
        val pageInfo = f.loader.loadClass("dev.viaduct.persistence.approvalfixture.PageInfo").kotlin
        val info = value.get<ObjectBase>("pageInfo", pageInfo)
        return listOf(
            nodes,
            info.get<Boolean>("hasNextPage", Boolean::class),
            info.get<Boolean>("hasPreviousPage", Boolean::class),
        )
    }

    private fun data(value: ObjectBase): EngineObjectData.Sync = value.__engineObject as EngineObjectData.Sync

    private fun get(
        value: ObjectBase,
        field: String,
    ): Any? = value.javaClass.getMethod("get$field").invoke(value)
}
