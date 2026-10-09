@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.orm.grt.GrtBinding
import dev.viaduct.persistence.orm.grt.GrtEntity
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateObjectTest.Companion.document
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateObjectTest.Companion.edge
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateObjectTest.Companion.record
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateSelectionTest.Companion.columns
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.Root
import org.hibernate.Session
import org.hibernate.query.SelectionQuery
import org.hibernate.query.criteria.JpaCriteriaQuery
import viaduct.api.internal.ObjectBase
import viaduct.api.types.Object
import kotlin.test.Test
import kotlin.test.assertEquals

/** Execute actual PostgreSQL projections, including bound predicates, paging and nested selections. */
class GeneratedDelegateQuerySelectionTest {
    @Test
    fun `node connection orders by unselected properties without hydrating entities`() =
        withFixture { f ->
            val alice = f.insert(f.person("alice"))
            f.insert(f.person("bob"))
            trackReads(f)
            val page = connection(f, "People", "Person", "edges { cursor node { username } } pageInfo { hasNextPage }")
            val first = edges(page).single()
            assertEquals(
                listOf(f.id(alice), true, listOf(setOf("_uuid_id")), 0L),
                listOf(
                    f.id(get(first, "Node") as ObjectBase),
                    get(get(page, "PageInfo") as ObjectBase, "HasNextPage"),
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `stored edge connection selects only its requested label and node identity`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val owner = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, owner, person, "links", "LinkEdge", "chosen")
            }
            trackReads(f)
            val page = connection(f, "Links", "Record${f.suffix}LinksAssociation", "edges { title: label node { id } }")
            val selected = edges(page).single()
            assertEquals(
                listOf(
                    "chosen",
                    f.id(person),
                    listOf(setOf("_viaduct_id", "label", "delegate_person${f.suffix}_id")),
                    0L,
                ),
                listOf(
                    get(selected, "Label"),
                    f.id(get(selected, "Node") as ObjectBase),
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `nodes only requests still read the edge node FK but omit stored edge fields`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val owner = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, owner, person, "links", "LinkEdge", "hidden")
            }
            trackReads(f)
            val page = connection(f, "Links", "Record${f.suffix}LinksAssociation", "nodes { id }")
            assertEquals(
                listOf(listOf(f.id(person)), listOf(setOf("_viaduct_id", "delegate_person${f.suffix}_id")), 0L),
                listOf(
                    (get(page, "Nodes") as List<*>).map { f.id(it as ObjectBase) },
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `bound edge filters and forward cursors preserve ordering without selecting owner or reviewer`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val owner = f.insert(record(f, person))
            val unrelated = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                listOf("a", "b", "c").forEach { edge(f, session, owner, person, "links", "LinkEdge", it) }
                edge(f, session, unrelated, person, "links", "LinkEdge", "aa")
            }
            trackReads(f)
            val configure = filteredEdges(f, owner)
            val first =
                connection(
                    f,
                    "Links",
                    "Record${f.suffix}LinksAssociation",
                    "edges { label cursor } pageInfo { hasNextPage }",
                    configure = configure,
                )
            val second =
                connection(
                    f,
                    "Links",
                    "Record${f.suffix}LinksAssociation",
                    "edges { label } pageInfo { hasPreviousPage hasNextPage }",
                    mapOf("first" to 1, "after" to get(edges(first).single(), "Cursor")),
                    configure,
                )
            assertEquals(
                listOf(
                    "a",
                    "b",
                    true,
                    true,
                    false,
                    listOf(
                        setOf("_viaduct_id", "label", "delegate_person${f.suffix}_id"),
                        setOf("_viaduct_id", "label", "delegate_person${f.suffix}_id"),
                    ),
                    0L,
                ),
                listOf(
                    get(edges(first).single(), "Label"),
                    get(edges(second).single(), "Label"),
                    get(get(first, "PageInfo") as ObjectBase, "HasNextPage"),
                    get(get(second, "PageInfo") as ObjectBase, "HasPreviousPage"),
                    get(get(second, "PageInfo") as ObjectBase, "HasNextPage"),
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `backward connection count and lookahead use native tuples`() =
        withFixture { f ->
            f.insert(f.person("alice"))
            val bob = f.insert(f.person("bob"))
            trackReads(f)
            val page =
                connection(
                    f,
                    "People",
                    "Person",
                    "edges { node { id } } pageInfo { hasPreviousPage hasNextPage }",
                    mapOf("last" to 1),
                )
            assertEquals(
                listOf(f.id(bob), true, false, true, false, 0L),
                listOf(
                    f.id(get(edges(page).single(), "Node") as ObjectBase),
                    get(get(page, "PageInfo") as ObjectBase, "HasPreviousPage"),
                    get(get(page, "PageInfo") as ObjectBase, "HasNextPage"),
                    f.selectStatements().any { "count(" in it },
                    f.selectStatements().any { "nickname" in columns(it) },
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `mixed abstract edge projections retain concrete target identities`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val owner = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, owner, person, "mixedLinks", "MixedEdge", "one")
                edge(f, session, owner, group, "mixedLinks", "MixedEdge", "two")
            }
            trackReads(f)
            val page =
                connection(
                    f,
                    "MixedLinks",
                    "Record${f.suffix}MixedLinksReference",
                    "nodes { __typename }",
                    mapOf("first" to 2),
                )
            assertEquals(
                listOf(setOf(f.id(person), f.id(group)), false, 0L),
                listOf(
                    (get(page, "Nodes") as List<*>).map { f.id(it as ObjectBase) }.toSet(),
                    f.selectStatements().any { "label" in columns(it) },
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `shared edge GRTs use the explicitly chosen association mapping`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val owner = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, owner, person, "links", "LinkEdge", "first")
                edge(f, session, owner, person, "moreLinks", "LinkEdge", "second")
            }
            trackReads(f)
            val page = connection(f, "Links", "Record${f.suffix}MoreLinksAssociation", "edges { label }")
            assertEquals(
                listOf("second", 0L),
                listOf(get(edges(page).single(), "Label"), f.factory.statistics.entityLoadCount),
            )
        }

    @Test
    fun `custom ordinary object queries support parameters ordering and finite child selections`() =
        withFixture(extended = true) { f ->
            val parent = f.insert(document(f, "parent"))
            f.insert(document(f, "child-b", parent))
            f.insert(document(f, "child-a", parent))
            trackReads(f)
            val values =
                f.client.transaction(f.context) { session ->
                    f.client.read(
                        f.context,
                        session,
                        binding(f, "Document"),
                        f.objectSelections("Document", "label parent { label }"),
                    ) { criteria, root ->
                        val cb = session.criteriaBuilder
                        criteria.where(cb.like(root.get("label"), cb.parameter(String::class.java, "prefix")))
                        criteria.orderBy(cb.asc(root.get<String>("label")))
                        session.createSelectionQuery(criteria).setParameter("prefix", "child-%").setMaxResults(1)
                    }
                }
            val selected = values.single() as ObjectBase
            assertEquals(
                listOf("child-a", "parent", false, 0L),
                listOf(
                    get(selected, "Label"),
                    get(get(selected, "Parent") as ObjectBase, "Label"),
                    f.selectStatements().any { "value" in columns(it) },
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `ordinary connection merges nodes and edge node property trees`() =
        withFixture(extended = true) { f ->
            val parent = f.insert(document(f, "parent"))
            f.insert(document(f, "child", parent))
            trackReads(f)
            val page =
                connection(
                    f,
                    "Documents",
                    "Document",
                    "nodes { label } edges { node { parent { label } } }",
                    configure = { session, criteria, root ->
                        criteria.where(session.criteriaBuilder.equal(root.get<String>("label"), "child"))
                        session.createSelectionQuery(criteria)
                    },
                )
            val selected = (get(page, "Nodes") as List<*>).single() as ObjectBase
            assertEquals(
                listOf("child", "parent", false, 0L),
                listOf(
                    get(selected, "Label"),
                    get(get(selected, "Parent") as ObjectBase, "Label"),
                    f.selectStatements().any { "value" in columns(it) },
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `selective connection rejects application paging before executing rows`() =
        withFixture { f ->
            f.insert(f.person("alice"))
            trackReads(f)
            val failure =
                runCatching {
                    connection(f, "People", "Person", "nodes { id }", configure = { session, criteria, _ ->
                        session.createSelectionQuery(criteria).setMaxResults(1)
                    })
                }.exceptionOrNull()
            assertEquals(
                listOf(true, emptyList<String>()),
                listOf(failure is IllegalArgumentException, f.selectStatements()),
            )
        }

    @Test
    fun `custom read rejects replacing the generated projection before executing rows`() =
        withFixture { f ->
            f.insert(f.person("alice"))
            trackReads(f)
            val failure =
                runCatching {
                    f.client.transaction(f.context) { session ->
                        f.client.read(
                            f.context,
                            session,
                            binding(f, "Person"),
                            f.objectSelections("Person", "username"),
                        ) { criteria, root ->
                            criteria.select(session.criteriaBuilder.tuple(root.get<String>("username")))
                            session.createSelectionQuery(criteria)
                        }
                    }
                }.exceptionOrNull()
            assertEquals(
                listOf(true, emptyList<String>()),
                listOf(failure is IllegalArgumentException, f.selectStatements()),
            )
        }

    @Test
    fun `distinct joined connection counts unique roots for backward paging`() =
        withFixture { f ->
            val first = f.insert(f.person("first"))
            val second = f.insert(f.person("second"))
            repeat(2) { f.insert(f.person("first-$it", first)) }
            repeat(3) { f.insert(f.person("second-$it", second)) }
            trackReads(f)
            val page =
                connection(
                    f,
                    "People",
                    "Person",
                    "nodes { id } pageInfo { hasPreviousPage }",
                    mapOf("last" to 1),
                    configure = { session, criteria, root ->
                        root.join<Any, Any>("reports")
                        criteria.distinct(true)
                        criteria.orderBy(session.criteriaBuilder.asc(root.get<java.util.UUID>("internalId")))
                        session.createSelectionQuery(criteria)
                    },
                )
            val expected = listOf(f.id(first), f.id(second)).maxBy { it.internalID }
            assertEquals(
                listOf(listOf(expected), true, false, 0L),
                listOf(
                    (get(page, "Nodes") as List<*>).map { f.id(it as ObjectBase) },
                    get(get(page, "PageInfo") as ObjectBase, "HasPreviousPage"),
                    f.selectStatements().any { "nickname" in columns(it) },
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `custom read keeps rows with null optional relationship FKs`() =
        withFixture(extended = true) { f ->
            f.insert(document(f, "root"))
            trackReads(f)
            val values =
                f.client.transaction(f.context) { session ->
                    f.client.read(
                        f.context,
                        session,
                        binding(f, "Document"),
                        f.objectSelections("Document", "label parent { label }"),
                    ) { criteria, _ ->
                        session.createSelectionQuery(criteria)
                    }
                }
            val selected = values.single() as ObjectBase
            assertEquals(
                listOf("root", null, false, 0L),
                listOf(
                    get(selected, "Label"),
                    get(selected, "Parent"),
                    f.selectStatements().any { "value" in columns(it) },
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `pageInfo only stored edge reads omit labels and still determine lookahead`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val owner = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, owner, person, "links", "LinkEdge", "one")
                edge(f, session, owner, person, "links", "LinkEdge", "two")
            }
            trackReads(f)
            val page = connection(f, "Links", "Record${f.suffix}LinksAssociation", "pageInfo { hasNextPage }")
            assertEquals(
                listOf(true, listOf(setOf("_viaduct_id", "delegate_person${f.suffix}_id")), 0L),
                listOf(
                    get(get(page, "PageInfo") as ObjectBase, "HasNextPage"),
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `selective connection rejects paging embedded in the Criteria query`() =
        withFixture { f ->
            f.insert(f.person("alice"))
            trackReads(f)
            val rejected =
                listOf("offset", "fetch").map { clause ->
                    runCatching {
                        connection(f, "People", "Person", "nodes { id }", configure = { session, criteria, _ ->
                            if (clause == "offset") criteria.offset(1) else criteria.fetch(1)
                            session.createSelectionQuery(criteria)
                        })
                    }.exceptionOrNull() is IllegalArgumentException
                }
            assertEquals(listOf(listOf(true, true), emptyList<String>()), listOf(rejected, f.selectStatements()))
        }

    private fun filteredEdges(
        f: GeneratedDelegateFixture,
        owner: ObjectBase,
    ): QuerySetup =
        { session, criteria, root ->
            val cb = session.criteriaBuilder
            criteria.where(
                cb.and(
                    cb.equal(
                        root.get<Any>("owner").get<java.util.UUID>("internalId"),
                        cb.parameter(java.util.UUID::class.java, "owner"),
                    ),
                    cb.lessThan(root.get("label"), cb.parameter(String::class.java, "until")),
                ),
            )
            criteria.orderBy(cb.asc(root.get<String>("label")), cb.asc(root.get<java.util.UUID>("internalId")))
            session
                .createSelectionQuery(criteria)
                .setParameter("owner", java.util.UUID.fromString(f.id(owner).internalID))
                .setParameter("until", "c")
        }

    @Suppress("LongParameterList") // Test helper keeps schema type, selections, paging and native query explicit.
    private suspend fun connection(
        f: GeneratedDelegateFixture,
        kind: String,
        row: String,
        fields: String,
        arguments: Map<String, Any?> = mapOf("first" to 1),
        configure: QuerySetup = { session, criteria, root ->
            val order = if (row == "Person") "username" else "internalId"
            val cb = session.criteriaBuilder
            criteria.orderBy(cb.asc(root.get<Any>(order)), cb.asc(root.get<java.util.UUID>("internalId")))
            session.createSelectionQuery(criteria)
        },
    ): ObjectBase =
        f.client.fetchConnection(
            f.connectionContext(arguments),
            f.connectionSelections(kind, fields),
            binding(f, row),
            configure,
        ) as ObjectBase

    @Suppress("UNCHECKED_CAST") // Fixture's generated binding is checked against its loaded GRT type by the client.
    private fun binding(f: GeneratedDelegateFixture, row: String): GrtBinding<Object> {
        val name = if (row.startsWith("Record")) "Delegate$row" else "Delegate$row${f.suffix}"
        return f.binding(name) as GrtBinding<Object>
    }

    private fun trackReads(f: GeneratedDelegateFixture) {
        f.clearStatements()
        f.factory.statistics.isStatisticsEnabled = true
        f.factory.statistics.clear()
    }

    private fun edges(page: ObjectBase): List<ObjectBase> = (get(page, "Edges") as List<*>).map { it as ObjectBase }

    private fun get(
        value: ObjectBase,
        suffix: String,
    ): Any? = value.javaClass.getMethod("get$suffix").invoke(value)
}

private typealias QuerySetup =
    (Session, JpaCriteriaQuery<Tuple>, Root<out GrtEntity<Object>>) -> SelectionQuery<Tuple>
