@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.DelegateFixture.Companion.withFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.hibernate.Hibernate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData
import viaduct.service.api.spi.GlobalIDCodec
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feasibility proof with actual, unmodified Viaduct GRT bytecode and native Hibernate/PostgreSQL. */
class HibernateGrtDelegateIntegrationTest {
    @Test
    fun `hibernate persists a GRT and assigns identity without changing the supplied value`() =
        withFixture { f ->
            val supplied = f.person("alice")
            val saved =
                f.transaction { session ->
                    val entity = DelegatePerson(f.binding()).also { it.initialize(supplied) }
                    session.persist(f.type.name, entity)
                    entity.value
                }
            val loaded = f.fetch(f.nodeContext(f.id(saved), "id username status"))
            assertEquals(
                listOf(false, "alice", "ACTIVE", f.id(saved).internalID),
                listOf(
                    (supplied.__engineObject as EngineObjectData.Sync).isPresent("id"),
                    get(loaded, "Username"),
                    (get(loaded, "Status") as Enum<*>).name,
                    f.id(loaded).internalID,
                ),
            )
        }

    @Test
    fun `hydration builds one GRT and unchanged flush produces no update`() =
        withFixture { f ->
            val id = f.insert("alice")
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val builds =
                f.transaction { session ->
                    val entity = f.find(session, id)
                    entity.value
                    session.flush()
                    entity.builds
                }
            assertEquals(listOf(1L, 0L), listOf(builds.toLong(), f.factory.statistics.entityUpdateCount))
        }

    @Test
    fun `replacement GRT drives dirty checking and leaves old cached and uncached fields stable`() =
        withFixture { f ->
            val id = f.insert("alice")
            val later = Instant.parse("2026-10-08T12:34:56Z")
            val snapshots =
                f.transaction { session ->
                    val entity = f.find(session, id)
                    val old = entity.value
                    get(old, "Username") // Populate the GRT getter cache before replacing it.
                    val replacement =
                        GrtDelegate
                            .toBuilder(old)
                            .put("username", "bob")
                            .put("status", f.binding().enumValue("INACTIVE"))
                            .put("happenedAt", later)
                            .build() as ObjectBase
                    entity.replace(replacement, session)
                    old to entity.value
                }
            val loaded = f.fetch(f.nodeContext(id, "username nickname status happenedAt"))
            assertEquals(
                listOf("alice", "ACTIVE", null, "bob", "INACTIVE", later, "bob", "initial", "INACTIVE", later),
                listOf(
                    get(snapshots.first, "Username"),
                    (get(snapshots.first, "Status") as Enum<*>).name,
                    get(snapshots.first, "HappenedAt"),
                    get(snapshots.second, "Username"),
                    (get(snapshots.second, "Status") as Enum<*>).name,
                    get(snapshots.second, "HappenedAt"),
                    get(loaded, "Username"),
                    get(loaded, "Nickname"),
                    (get(loaded, "Status") as Enum<*>).name,
                    get(loaded, "HappenedAt"),
                ),
            )
        }

    @Test
    fun `hibernate refresh replaces the GRT without invalidating an old snapshot`() =
        withFixture { f ->
            val id = f.insert("alice")
            val names =
                f.transaction { session ->
                    val entity = f.find(session, id)
                    val old = entity.value
                    session
                        .createMutationQuery("update ${f.type.name} set username = :name where internalId = :id")
                        .setParameter("name", "bob")
                        .setParameter("id", entity.internalId)
                        .executeUpdate()
                    session.refresh(entity)
                    listOf(get(old, "Username"), get(entity.value, "Username"))
                }
            assertEquals(listOf("alice", "bob"), names)
        }

    @Test
    fun `explicit null clears a field and toBuilder preserves fields that were not changed`() =
        withFixture { f ->
            val id = f.insert("alice")
            f.transaction { session ->
                val entity = f.find(session, id)
                val replacement = GrtDelegate.toBuilder(entity.value).put("nickname", null).build() as ObjectBase
                entity.replace(replacement, session)
            }
            val loaded = f.fetch(f.nodeContext(id, "username nickname status"))
            assertEquals(
                listOf("alice", null, "ACTIVE"),
                listOf(get(loaded, "Username"), get(loaded, "Nickname"), (get(loaded, "Status") as Enum<*>).name),
            )
        }

    @Test
    fun `partial replacement is rejected before altering managed state`() =
        withFixture { f ->
            val id = f.insert("alice")
            val result =
                f.transaction { session ->
                    val entity = f.find(session, id)
                    val partial =
                        GrtDelegate
                            .toBuilder(entity.selected(setOf("id", "username")))
                            .put("username", "bob")
                            .build() as ObjectBase
                    val failure = runCatching { entity.replace(partial, session) }.exceptionOrNull()
                    listOf(failure is IllegalArgumentException, entity.username, entity.nickname)
                }
            assertEquals(listOf(true, "alice", "initial"), result)
        }

    @Test
    fun `selected views share immutable data and toBuilder cannot expose omitted fields`() =
        withFixture { f ->
            val id = f.insert("alice")
            val result =
                f.transaction { session ->
                    val entity = f.find(session, id)
                    val full = entity.value
                    val selected = entity.selected(setOf("username"))
                    val edited = GrtDelegate.toBuilder(selected).put("username", "bob").build() as ObjectBase
                    val selectedData = selected.__engineObject as SelectedData
                    listOf(
                        selectedData.base === full.__engineObject,
                        entity.builds,
                        get(selected, "Username"),
                        get(edited, "Username"),
                        (edited.__engineObject as EngineObjectData.Sync).isPresent("nickname"),
                        runCatching { get(edited, "Nickname") }.isFailure,
                    )
                }
            assertEquals(listOf(true, 1, "alice", "bob", false, true), result)
        }

    @Test
    fun `same managed identity provides separate selected GRT views`() =
        withFixture { f ->
            val id = f.insert("alice")
            val result =
                f.transaction { session ->
                    val entity = f.find(session, id)
                    val name = entity.selected(setOf("username"))
                    val nickname = f.find(session, id).selected(setOf("nickname"))
                    listOf(
                        entity === f.find(session, id),
                        get(name, "Username"),
                        get(nickname, "Nickname"),
                        runCatching { get(name, "Nickname") }.isFailure,
                        runCatching { get(nickname, "Username") }.isFailure,
                    )
                }
            assertEquals(listOf(true, "alice", "initial", true, true), result)
        }

    @Test
    fun `native association remains a detached Viaduct reference outside owned selections`() =
        withFixture { f ->
            val manager = f.insert("manager")
            val id = f.insert("alice")
            f.transaction { session ->
                val entity = f.find(session, id)
                val ref = f.context.ref(manager)
                val replacement = GrtDelegate.toBuilder(entity.value).put("manager", ref).build() as ObjectBase
                entity.replace(replacement, session)
            }
            val base = f.nodeContext(id, "username manager { id }")
            val context =
                object : SelectiveNodeExecutionContext<NodeObject> by base, InternalContext by f.internal {
                    override fun ownedSelections() = f.nodeContext(id, "username").ownedSelections()
                }
            val loaded = f.fetch(context)
            val ref = get(loaded, "Manager") as ObjectBase
            val refId = ref.get<GlobalID<*>>("id", GlobalID::class)
            assertEquals(
                listOf("alice", manager.internalID, true),
                listOf(get(loaded, "Username"), refId.internalID, runCatching { get(ref, "Username") }.isFailure),
            )
        }

    @Test
    fun `native lazy proxy can be loaded as a GRT without replacing Hibernate identity`() =
        withFixture { f ->
            val id = f.insert("alice")
            val result =
                f.transaction { session ->
                    val proxy = session.getReference(f.type.name, UUID.fromString(id.internalID))
                    val initiallyLoaded = Hibernate.isInitialized(proxy)
                    val entity = f.find(session, id)
                    listOf(
                        initiallyLoaded,
                        get(entity.value, "Username"),
                        Hibernate.isInitialized(proxy),
                        entity === f.find(session, id),
                    )
                }
            assertEquals(listOf(false, "alice", true, true), result)
        }

    @Test
    fun `native remove deletes the row`() =
        withFixture { f ->
            val id = f.insert("alice")
            f.transaction { session -> session.remove(f.find(session, id)) }
            val missing = f.transaction { session -> session.find(f.type.name, UUID.fromString(id.internalID)) }
            assertEquals(null, missing)
        }

    @ParameterizedTest
    @ValueSource(strings = ["abort", "constraint", "cancel", "fatal"])
    fun `delegate transaction rolls back failures without hanging`(case: String) =
        withFixture { f ->
            val fatal = LinkageError("fatal")
            val failure =
                withTimeout(10000) {
                    supervisorScope {
                        val job =
                            async {
                                val coroutine = currentCoroutineContext()
                                val supplied = f.person("alice")
                                f.transaction { session ->
                                    val person = DelegatePerson(f.binding())
                                    person.initialize(supplied)
                                    session.persist(f.type.name, person)
                                    when (case) {
                                        "abort" -> error("abort")
                                        "constraint" -> {
                                            val duplicate = DelegatePerson(f.binding())
                                            duplicate.initialize(supplied)
                                            session.persist(f.type.name, duplicate)
                                        }
                                        "cancel" -> coroutine.cancel()
                                        else -> throw fatal
                                    }
                                }
                            }
                        runCatching { job.await() }.exceptionOrNull()
                    }
                }
            val count =
                f.transaction { session ->
                    val query = "select count(p) from ${f.type.name} p"
                    session.createSelectionQuery(query, java.lang.Long::class.java).singleResult.toLong()
                }
            val failedAsExpected =
                if (case == "fatal") {
                    generateSequence(failure) { it.cause }.any { it === fatal }
                } else {
                    failure != null
                }
            assertEquals(
                listOf(true, 0L),
                listOf(failedAsExpected, count),
            )
        }

    @Test
    fun `parallel sessions retain their own contexts and global ID codecs`() =
        withFixture { f ->
            val id = f.insert("alice")
            val results =
                withTimeout(10000) {
                    supervisorScope {
                        listOf("first", "second")
                            .map { caller ->
                                async {
                                    val context = callerContext(f, caller)
                                    f.transaction(context) { session ->
                                        val entity = f.find(session, id)
                                        val data = entity.value.__engineObject as EngineObjectData.Sync
                                        listOf(
                                            entity.requestContext,
                                            data.get("id").toString().startsWith("$caller:"),
                                            get(entity.value, "Username"),
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                }
            assertEquals(listOf(listOf("first", true, "alice"), listOf("second", true, "alice")), results)
        }

    @Test
    fun `real Viaduct execution supports aliases and nested references from delegates`() =
        withFixture { f ->
            val manager = f.insert("manager")
            val id = f.insert("alice")
            f.transaction { session -> f.find(session, id).manager = f.find(session, manager) }
            val expectedPerson = mapOf("label" to "alice", "manager" to mapOf("label" to "manager"))
            assertEquals(
                listOf(
                    mapOf("person" to expectedPerson, "healthy" to "ok"),
                    emptyList<Any>(),
                ),
                execute(f, id, "{ person { label: username manager { label: username } } healthy }"),
            )
        }

    @Test
    fun `checker denial retains aliases sibling data and nonnull bubbling`() =
        withFixture { f ->
            val id = f.insert("alice")
            assertEquals(
                listOf(mapOf("subject" to null, "healthy" to "ok"), listOf(listOf("subject", "label"))),
                execute(f, id, "{ subject: person { label: username } healthy }", denied = true),
            )
        }

    @Test
    fun `missing delegate node retains sibling data and the GraphQL error path`() =
        withFixture { f ->
            val id =
                f.context.globalIDFor(
                    f.type,
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
            assertEquals(
                listOf(mapOf("subject" to null, "healthy" to "ok"), listOf(listOf("subject"))),
                execute(f, id, "{ subject: person { username } healthy }"),
            )
        }

    private suspend fun execute(
        f: DelegateFixture,
        id: GlobalID<NodeObject>,
        query: String,
        denied: Boolean = false,
    ): List<Any?> = f.execute(id, query, denied)

    private fun callerContext(
        f: DelegateFixture,
        caller: String,
    ): ResolverExecutionContext<Query> =
        object : ResolverExecutionContext<Query> by f.context, InternalContext by f.internal {
            override val requestContext: Any = caller
            override val globalIDCodec =
                object : GlobalIDCodec {
                    override fun serialize(
                        typeName: String,
                        localID: String,
                    ) = "$caller:" + f.internal.globalIDCodec.serialize(typeName, localID)

                    override fun deserialize(globalID: String): viaduct.service.api.spi.DecodedGlobalID {
                        val serialized = globalID.removePrefix("$caller:")
                        return f.internal.globalIDCodec.deserialize(serialized)
                    }
                }

            override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> =
                f.internal.deserializeGlobalID(serialized.removePrefix("$caller:"))
        }

    private fun get(
        value: ObjectBase,
        field: String,
    ): Any? = value.javaClass.getMethod("get$field").invoke(value)
}
