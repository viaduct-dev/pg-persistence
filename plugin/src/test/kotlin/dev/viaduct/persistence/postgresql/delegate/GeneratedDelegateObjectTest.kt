@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import kotlinx.coroutines.withTimeout
import org.hibernate.Session
import viaduct.api.internal.ObjectBase
import viaduct.api.types.Object
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Actual codegen output and native PostgreSQL, including storage rows without GraphQL types. */
class GeneratedDelegateObjectTest {
    @Test
    fun `union and interface fields retain their concrete Node GRT and nullability`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val record = f.insert(record(f, person, group))
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(record), "subject { __typename } actor { id }"))
            assertEquals(
                listOf(f.id(person), f.id(group)),
                listOf(f.id(get(selected, "Subject") as ObjectBase), f.id(get(selected, "Actor") as ObjectBase)),
            )
        }

    @Test
    fun `abstract replacement changes concrete target without mutating previous GRT`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val saved = f.insert(record(f, person, person))
            val old =
                f.client.transaction(f.context) { session ->
                    val current = f.access.find(f.client, f.context, session, f.id(saved))
                    f.access.update(
                        f.client,
                        f.context,
                        session,
                        GrtDelegate
                            .toBuilder(current)
                            .put("subject", group)
                            .put("actor", null)
                            .build() as ObjectBase,
                    )
                    current
                }
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "subject { __typename } actor { id }"))
            assertEquals(
                listOf(f.id(person), f.id(group), null),
                listOf(
                    f.id(get(old, "Subject") as ObjectBase),
                    f.id(get(selected, "Subject") as ObjectBase),
                    get(selected, "Actor"),
                ),
            )
        }

    @Test
    fun `invalid abstract replacement leaves managed state and database unchanged`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val saved = f.insert(record(f, person))
            val failed =
                f.client.transaction(f.context) { session ->
                    val current = f.access.find(f.client, f.context, session, f.id(saved))
                    runCatching {
                        f.access.update(
                            f.client,
                            f.context,
                            session,
                            GrtDelegate.toBuilder(current).put("subject", null).build() as ObjectBase,
                        )
                    }.isFailure
                }
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "subject { __typename }"))
            assertEquals(listOf(true, f.id(person)), listOf(failed, f.id(get(selected, "Subject") as ObjectBase)))
        }

    @Test
    fun `mixed abstract lists avoid child hydration and bag loading`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val saved = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                listOf(person, group).forEach { target ->
                    listOf("subjects", "actors").forEach { field -> storageRow(f, session, saved, target, field) }
                }
            }
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "subjects { __typename } actors { id }"))
            val lists =
                listOf("Subjects", "Actors").map { field ->
                    (get(selected, field) as List<*>).map { f.id(it as ObjectBase) }.toSet()
                }
            assertEquals(
                listOf(setOf(f.id(person), f.id(group)), setOf(f.id(person), f.id(group)), 1L, 0L),
                lists + listOf(f.factory.statistics.entityLoadCount, f.factory.statistics.collectionLoadCount),
            )
        }

    @Test
    fun `non Node scalars and identity hydrate into a concrete GRT with ordinary field names`() =
        withFixture(extended = true) { f ->
            val saved = f.insert(document(f, "first"))
            val id = get(saved, "Id") as String
            val projected =
                f.client.transaction(f.context) { session ->
                    val entity =
                        f.objectEntity(
                            checkNotNull(session.find("DelegateDocument${f.suffix}", UUID.fromString(id))),
                        )
                    f.client.project(
                        f.context,
                        session,
                        entity,
                        f.objectSelections(
                            "Document",
                            "id label value context binding builder current " +
                                "pending native_label",
                        ),
                    ) as ObjectBase
                }
            assertEquals(
                listOf(id, "first") +
                    List(7) {
                        "ordinary"
                    },
                listOf(
                    "Id",
                    "Label",
                    "Value",
                    "Context",
                    "Binding",
                    "Builder",
                    "Current",
                    "Pending",
                    "Native_label",
                ).map { get(projected, it) },
            )
        }

    @Test
    fun `cyclic non Node relationships are bounded by finite public selections`() =
        withFixture(extended = true) { f ->
            val a = f.insert(document(f, "a"))
            val b = f.insert(document(f, "b", a))
            f.client.transaction(f.context) { session ->
                val entity =
                    f.objectEntity(
                        checkNotNull(
                            session.find("DelegateDocument${f.suffix}", UUID.fromString(get(a, "Id") as String)),
                        ),
                    )
                entity.assign(
                    GrtDelegate.toBuilder(entity.grt() as ObjectBase).put("parent", b).build() as Object,
                    session,
                )
            }
            val projected =
                withTimeout(10000) {
                    f.client.transaction(f.context) { session ->
                        val entity =
                            f.objectEntity(
                                checkNotNull(
                                    session.find(
                                        "DelegateDocument${f.suffix}",
                                        UUID.fromString(get(a, "Id") as String),
                                    ),
                                ),
                            )
                        f.client.project(
                            f.context,
                            session,
                            entity,
                            f.objectSelections("Document", "label parent { label parent { label } }"),
                        ) as ObjectBase
                    }
                }
            val parent = get(projected, "Parent") as ObjectBase
            val grandparent = get(parent, "Parent") as ObjectBase
            assertEquals(
                listOf(
                    "a",
                    "b",
                    "a",
                    false,
                ),
                listOf(
                    get(projected, "Label"),
                    get(parent, "Label"),
                    get(grandparent, "Label"),
                    runCatching {
                        get(grandparent, "Parent")
                    }.isSuccess,
                ),
            )
        }

    @Test
    fun `non Node relationship selections detach values before session closure`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val document = f.insert(document(f, "details"))
            val saved = f.insert(record(f, person, document = document))
            val projected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "document { label }"))
            assertEquals(
                listOf("details", false),
                listOf(
                    get(get(projected, "Document") as ObjectBase, "Label"),
                    runCatching {
                        get(get(projected, "Document") as ObjectBase, "Id")
                    }.isSuccess,
                ),
            )
        }

    @Test
    fun `persisted edge fields project from concrete edge GRT delegates`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val saved = f.insert(record(f, person))
            f.client.transaction(
                f.context,
            ) { session -> edge(f, session, saved, person, "links", "LinkEdge", "priority") }
            val page =
                f.customConnection(
                    mapOf("first" to 1),
                    kind = "Links",
                    query = "from DelegateRecord${f.suffix}LinksAssociation e order by e.internalId",
                )
            val projected = (get(page, "Edges") as List<*>).single() as ObjectBase
            assertEquals(
                listOf("priority", f.id(person)),
                listOf(get(projected, "Label"), f.id(get(projected, "Node") as ObjectBase)),
            )
        }

    @Test
    fun `mixed abstract edge pages retain custom fields concrete nodes and modern cursors`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val saved = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, saved, person, "mixedLinks", "MixedEdge", "one")
                edge(f, session, saved, group, "mixedLinks", "MixedEdge", "two")
            }
            val query = "from DelegateRecord${f.suffix}MixedLinksReference e order by e.label, e.internalId"
            val first = f.customConnection(mapOf("first" to 1), kind = "MixedLinks", query = query)
            val firstEdge = (get(first, "Edges") as List<*>).single() as ObjectBase
            val second =
                f.customConnection(
                    mapOf("first" to 1, "after" to get(firstEdge, "Cursor")),
                    kind = "MixedLinks",
                    query = query,
                )
            val secondEdge = (get(second, "Edges") as List<*>).single() as ObjectBase
            assertEquals(
                listOf("one", f.id(person), "two", f.id(group), true, false),
                listOf(
                    get(firstEdge, "Label"),
                    f.id(get(firstEdge, "Node") as ObjectBase),
                    get(secondEdge, "Label"),
                    f.id(get(secondEdge, "Node") as ObjectBase),
                    get(get(first, "PageInfo") as ObjectBase, "HasNextPage"),
                    get(get(second, "PageInfo") as ObjectBase, "HasNextPage"),
                ),
            )
        }

    @Test
    fun `delegate object edge and storage row metadata preserves generated database schema`() =
        withFixture(extended = true) { f -> assertEquals(true, f.schemaUnchanged) }

    @Test
    fun `real GraphQL execution resolves abstract nodes and selected ordinary objects`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val group = f.insert(f.builder("Group").put("name", "team").build() as ObjectBase)
            val parent = f.insert(document(f, "parent"))
            val saved = f.insert(record(f, person, group, f.insert(document(f, "details", parent))))
            val query =
                "{ record { subject { ... on DelegatePerson${f.suffix} { username } } " +
                    "actor { ... on DelegateGroup${f.suffix} { name } } document { label parent { label } } } healthy }"
            assertEquals(
                listOf(
                    mapOf(
                        "record" to
                            mapOf(
                                "subject" to mapOf("username" to "alice"),
                                "actor" to mapOf("name" to "team"),
                                "document" to mapOf("label" to "details", "parent" to mapOf("label" to "parent")),
                            ),
                        "healthy" to "ok",
                    ),
                    emptyList<Any>(),
                ),
                GeneratedDelegateExecutionTest().execute(f, f.id(saved), query),
            )
        }

    @Test
    fun `ordinary object field checkers preserve GraphQL errors and sibling data`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val saved = f.insert(record(f, person, document = f.insert(document(f, "details"))))
            assertEquals(
                listOf(
                    mapOf("record" to mapOf("label" to "record", "document" to null), "healthy" to "ok"),
                    listOf(listOf("record", "document", "label")),
                ),
                GeneratedDelegateExecutionTest().execute(
                    f,
                    f.id(saved),
                    "{ record { label document { label } } healthy }",
                    deniedObject = true,
                ),
            )
        }

    @Test
    fun `native persisted edge replacement uses dirty checking and preserves old GRTs`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val saved = f.insert(record(f, person))
            val name = "DelegateRecord${f.suffix}LinksAssociation"
            f.client.transaction(
                f.context,
            ) { session -> edge(f, session, saved, person, "links", "LinkEdge", "before") }
            val old =
                f.client.transaction(f.context) { session ->
                    val entity =
                        f.objectEntity(
                            session.createSelectionQuery("from $name", Any::class.java).singleResult,
                        )
                    val current = entity.grt() as ObjectBase
                    entity.assign(GrtDelegate.toBuilder(current).put("label", "after").build() as Object, session)
                    current
                }
            val page =
                f.customConnection(
                    mapOf("first" to 1),
                    kind = "Links",
                    query = "from $name e order by e.internalId",
                )
            val loaded = (get(page, "Edges") as List<*>).single() as ObjectBase
            assertEquals(listOf("before", "after"), listOf(get(old, "Label"), get(loaded, "Label")))
        }

    @Test
    fun `single member abstract lists use concrete references without tuple shape assumptions`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val saved = f.insert(record(f, person))
            f.client.transaction(f.context) { session -> storageRow(f, session, saved, person, "singles") }
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "singles { __typename }"))
            assertEquals(listOf(f.id(person)), (get(selected, "Singles") as List<*>).map { f.id(it as ObjectBase) })
        }

    @Test
    fun `one edge GRT can back separate association mappings without mixing their fields`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val saved = f.insert(record(f, person))
            f.client.transaction(f.context) { session ->
                edge(f, session, saved, person, "links", "LinkEdge", "one")
                edge(f, session, saved, person, "moreLinks", "LinkEdge", "two")
            }
            val labels =
                listOf("Links", "MoreLinks").map { field ->
                    val query = "from DelegateRecord${f.suffix}${field}Association e order by e.internalId"
                    val page = f.customConnection(mapOf("first" to 1), "Links", query)
                    get((get(page, "Edges") as List<*>).single() as ObjectBase, "Label")
                }
            assertEquals(listOf("one", "two"), labels)
        }

    companion object {
        private fun get(
            value: ObjectBase,
            suffix: String,
        ): Any? = value.javaClass.getMethod("get$suffix").invoke(value)

        private fun record(
            f: GeneratedDelegateFixture,
            subject: ObjectBase,
            actor: ObjectBase? = null,
            document: ObjectBase? = null,
        ): ObjectBase =
            f
                .builder(
                    "Record",
                ).put("label", "record")
                .put("subject", subject)
                .put("actor", actor)
                .put("document", document)
                .build() as ObjectBase

        private fun document(
            f: GeneratedDelegateFixture,
            label: String,
            parent: ObjectBase? = null,
        ): ObjectBase {
            val builder = f.builder("Document").put("label", label).put("parent", parent)
            listOf("value", "context", "binding", "builder", "current", "pending", "native_label").forEach {
                builder.put(it, "ordinary")
            }
            return builder.build() as ObjectBase
        }

        private fun set(
            entity: Any,
            field: String,
            value: Any?,
        ) {
            entity.javaClass.methods
                .single { it.name == "set${field.replaceFirstChar(Char::uppercaseChar)}" }
                .invoke(entity, value)
        }

        private fun storageRow(
            f: GeneratedDelegateFixture,
            session: Session,
            owner: ObjectBase,
            target: ObjectBase,
            field: String,
        ) {
            val name = "DelegateRecord${f.suffix}${field.replaceFirstChar(Char::uppercaseChar)}Reference"
            val row =
                f.loader
                    .loadClass("dev.viaduct.persistence.approvalfixture.persistence.${name}Row")
                    .getConstructor()
                    .newInstance()
            set(row, "owner", f.native(session, f.id(owner)))
            set(row, "node${f.id(target).type.name}", f.native(session, f.id(target)))
            session.persist(name, row)
        }

        @Suppress("LongParameterList") // Native edge ownership is deliberately explicit in the fixture.
        private fun edge(
            f: GeneratedDelegateFixture,
            session: Session,
            owner: ObjectBase,
            target: ObjectBase,
            field: String,
            kind: String,
            label: String,
        ) {
            val name =
                "DelegateRecord${f.suffix}${field.replaceFirstChar(Char::uppercaseChar)}" +
                    if (kind == "LinkEdge") "Association" else "Reference"
            val entity = f.objectEntity(f.binding(name).create(f.context))
            val builder = f.builder(kind).put("node", target).put("label", label)
            if (kind == "LinkEdge") builder.put("reviewer", null)
            entity.assign(builder.build() as Object, session)
            set(entity, "owner", f.native(session, f.id(owner)))
            session.persist(name, entity)
        }
    }
}
