@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.orm.grt.GlobalIdType
import dev.viaduct.persistence.orm.grt.NodeGrtBinding
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import org.hibernate.Hibernate
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Native Hibernate identifier operations; no caller-side UUID parsing or request-bound type mapper. */
class GeneratedGlobalIdTest {
    @Test
    fun `hibernate assigns typed GlobalIDs while generated physical schema stays unchanged`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val result =
                f.client.transaction(f.context) { session ->
                    val entity = session.find(f.id(saved).type.name, f.id(saved))
                    listOf(session.getIdentifier(entity), nodeBinding(f, "Person").idClass, f.schemaUnchanged)
                }
            assertEquals(listOf(f.id(saved), GlobalID::class.java, true), result)
        }

    @Test
    fun `find and getReference accept GlobalIDs and share the persistence context`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val id = f.id(saved)
            val result =
                f.client.transaction(f.context) { session ->
                    val reference = session.getReference(id.type.name, id)
                    val loaded = session.find(id.type.name, id)
                    listOf(Hibernate.unproxy(reference) === Hibernate.unproxy(loaded), session.getIdentifier(loaded))
                }
            assertEquals(listOf(true, id), result)
        }

    @Test
    fun `wrong Node types are rejected even when the requested UUID is in the persistence context`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val result =
                f.client.transaction(f.context) { session ->
                    session.find(f.id(saved).type.name, f.id(saved))
                    val wrong = f.context.globalIDFor(f.types.getValue("Group"), f.id(saved).internalID)
                    runCatching { session.find(f.id(saved).type.name, wrong) }.isFailure
                }
            assertEquals(true, result)
        }

    @Test
    fun `UUID spelling variants retain lookup equality and canonical selective results`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val id = f.id(saved)
            val upper = f.context.globalIDFor(id.type, id.internalID.uppercase())
            val same =
                f.client.transaction(f.context) { session ->
                    session.find(id.type.name, id) === session.find(id.type.name, upper)
                }
            val selected = f.access.fetch(f.client, f.nodeContext(upper, "id username"))
            val updated =
                f.client.transaction(f.context) { session ->
                    val loaded = f.access.find(f.client, f.context, session, upper)
                    f.access.update(f.client, f.context, session, loaded)
                }
            assertEquals(listOf(true, id, id), listOf(same, f.id(selected), f.id(updated)))
        }

    @Test
    fun `typed identifier projections and HQL parameters round trip without entity hydration`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val projected =
                f.client.transaction(f.context) { session ->
                    session
                        .createSelectionQuery(
                            "select p.internalId from ${f.id(saved).type.name} p where p.internalId = :id",
                            nodeBinding(f, "Person").idClass,
                        ).setParameter("id", f.id(saved))
                        .resultList
                }
            assertEquals(listOf(listOf(f.id(saved)), 0L), listOf(projected, f.factory.statistics.entityLoadCount))
        }

    @Test
    fun `multiLoad handles GlobalID arrays independently for different Node mappings`() =
        withFixture { f ->
            val people = listOf(f.insert(f.person("alice")), f.insert(f.person("bob")))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val results =
                f.client.transaction(f.context) { session ->
                    val persons =
                        session
                            .byMultipleIds<Any>(f.id(people.first()).type.name)
                            .multiLoad(people.map(f::id))
                    val groups = session.byMultipleIds<Any>(f.id(group).type.name).multiLoad(listOf(f.id(group)))
                    (persons + groups).map { session.getIdentifier(it) }
                }
            assertEquals(people.map(f::id) + f.id(group), results)
        }

    @Test
    fun `cache serialization stores only UUIDs and restores the mapped graph type`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val type = GlobalIdType(f.types.getValue("Person"))
            val cached = type.disassemble(f.id(saved))
            val restored = type.assemble(serializedCopy(cached), null)
            assertEquals(
                listOf(UUID::class.java, f.id(saved), false, true),
                listOf(cached?.javaClass, restored, type.isMutable(), type.deepCopy(restored) === restored),
            )
        }

    @Test
    fun `second level cache restores IDs and associations into the current execution`() =
        withFixture(cached = true) { f ->
            val manager = f.insert(f.person("manager"))
            val saved = f.insert(f.person("alice", manager))
            f.client.transaction(f.context) { session -> f.access.find(f.client, f.context, session, f.id(saved)) }
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            f.clearStatements()
            val context =
                object :
                    ResolverExecutionContext<Query> by f.context,
                    viaduct.api.internal.InternalContext by f.internal {
                    override val requestContext: Any = Any()
                }
            val loaded =
                f.client.transaction(context) { session ->
                    f.access.find(f.client, context, session, f.id(saved))
                }
            assertEquals(
                listOf(f.id(saved), f.id(manager), true, emptyList<String>()),
                listOf(
                    f.id(loaded),
                    f.id(loaded.javaClass.getMethod("getManager").invoke(loaded) as ObjectBase),
                    f.factory.statistics.secondLevelCacheHitCount > 0,
                    f.selectStatements(),
                ),
            )
        }

    @Test
    fun `query cache restores typed identifier tuples`() =
        withFixture(cached = true) { f ->
            val saved = f.insert(f.person("alice"))

            suspend fun query() =
                f.client.transaction(f.context) { session ->
                    session
                        .createSelectionQuery(
                            "select p.internalId from ${f.id(saved).type.name} p where p.internalId = :id",
                            nodeBinding(f, "Person").idClass,
                        ).setParameter("id", f.id(saved))
                        .setCacheable(true)
                        .resultList
                }
            query()
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            f.clearStatements()
            val loaded = query()
            assertEquals(
                listOf(listOf(f.id(saved)), 1L, emptyList<String>()),
                listOf(loaded, f.factory.statistics.queryCacheHitCount, f.selectStatements()),
            )
        }

    @Test
    fun `wrong types in bound identifier parameters are rejected without matching another Node`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val wrong = f.context.globalIDFor(f.types.getValue("Group"), f.id(saved).internalID)
            val rejected =
                runCatching {
                    f.client.transaction(f.context) { session ->
                        session
                            .createSelectionQuery(
                                "from ${f.id(saved).type.name} p where p.internalId = :id",
                                Any::class.java,
                            ).setParameter("id", wrong)
                            .resultList
                    }
                }.isFailure
            assertEquals(true, rejected)
        }

    @Test
    fun `criteria identifier values use the mapped Node type`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val binding = nodeBinding(f, "Person")
            val result =
                f.client.transaction(f.context) { session ->
                    val cb = session.criteriaBuilder
                    val query = cb.createQuery(binding.idClass)
                    val person = query.from(binding.entityClass)
                    val id = person.get<GlobalID<NodeObject>>("internalId")
                    query.select(id).where(cb.equal(id, f.id(saved)))
                    session.createSelectionQuery(query).resultList
                }
            assertEquals(listOf(f.id(saved)), result)
        }

    @Test
    fun `bulk loading rejects identifiers from another Node type`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val wrong = f.context.globalIDFor(f.types.getValue("Group"), f.id(saved).internalID)
            val rejected =
                runCatching {
                    f.client.transaction(f.context) { session ->
                        session.byMultipleIds<Any>(f.id(saved).type.name).multiLoad(listOf(wrong))
                    }
                }.isFailure
            assertEquals(true, rejected)
        }

    @Suppress("UNCHECKED_CAST") // This fixture's generated binding carries its loaded Node type and matching ID class.
    private fun nodeBinding(f: GeneratedDelegateFixture, name: String): NodeGrtBinding<NodeObject> =
        f.binding("Delegate$name${f.suffix}") as NodeGrtBinding<NodeObject>

    private fun serializedCopy(value: Serializable?): Serializable? {
        val bytes =
            ByteArrayOutputStream().use { buffer ->
                ObjectOutputStream(buffer).use { it.writeObject(value) }
                buffer.toByteArray()
            }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as Serializable? }
    }
}
