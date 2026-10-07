@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.DbLookup
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.PgGraphqlOrder
import dev.viaduct.persistence.runtime.db.PgGraphqlOrderDirection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.CompositeField
import viaduct.api.types.Connection
import viaduct.api.types.Edge
import viaduct.api.types.NodeObject
import viaduct.api.types.OffsetCursor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every request executes real graphql.resolve against isolated schema-generated tables. */
class LookupIntegrationTest {
    @Test
    fun `foreign key lookup returns all rows without requiring uniqueness`() =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val person = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(person)
                val ids = (1..2).map { index -> insertMember(f, "Membership", person, index) }
                val lookup = DbLookup.by(reference(f, "Membership", "person"))
                assertEquals(ids.toSet(), f.lookup(lookup, person).map { (it as ObjectBase).internalId() }.toSet())
            }
        }

    @Test
    fun `composite conditions project one reference per source row across provider pages`() =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val person = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(person)
                repeat(35) { index -> insertMember(f, "Membership", person, index + 1) }
                val outsider = f.createRequest("AccessRequest", "requestedPermission", "VIEWER")
                insertMember(f, "Membership", outsider, 100)
                val lookup =
                    DbLookup
                        .where<List<GlobalID<NodeObject>>, NodeObject>(f.type("Membership${f.suffix}")) {
                            PgGraphqlFilter.allOf(
                                PgGraphqlFilter.oneOf("personId", it),
                                PgGraphqlFilter.eq("label", "row"),
                            )
                        }.project(reference(f, "Membership", "person"))
                assertEquals(
                    List(35) { person.internalID },
                    f.lookup(lookup, listOf(person)).map { (it as ObjectBase).internalId() },
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["first", "last", "after", "before"])
    fun `projected root connections preserve source row positions and metadata`(direction: String) =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val person = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(person)
                repeat(35) { index -> insertMember(f, "Membership", person, index + 1) }
                val lookup =
                    DbLookup
                        .by(reference(f, "Membership", "person"))
                        .project(reference(f, "Membership", "person"))
                val (arguments, offsets) =
                    when (direction) {
                        "first" -> mapOf("first" to 35) to (0..34).toList()
                        "last" -> mapOf("last" to 5) to (30..34).toList()
                        "after" -> mapOf("first" to 2, "after" to OffsetCursor.fromOffset(10).value) to listOf(11, 12)
                        else -> mapOf("last" to 2, "before" to OffsetCursor.fromOffset(10).value) to listOf(8, 9)
                    }
                val connection =
                    f.lookupConnection(
                        lookup,
                        person,
                        "LabeledAccessConnection",
                        "edges { cursor label node { id } } nodes { id } pageInfo { hasNextPage hasPreviousPage }",
                        arguments,
                        listOf(PgGraphqlOrder("uuidId", PgGraphqlOrderDirection.ASC_NULLS_LAST)),
                    )
                val edges = connection.get<List<ObjectBase>>("edges", f.reflection("LabeledAccessEdge${f.suffix}").kcls)
                val nodes = connection.get<List<ObjectBase>>("nodes", person.type.kcls)
                val info = connection.get<ObjectBase>("pageInfo", f.reflection("PageInfo").kcls)
                assertEquals(
                    Triple(
                        offsets.map { Triple(OffsetCursor.fromOffset(it).value, "row", person.internalID) },
                        List(offsets.size) { person.internalID },
                        (direction in setOf("after", "before")) to (direction != "first"),
                    ),
                    Triple(
                        edges.map {
                            Triple(
                                it.get<String>("cursor", String::class),
                                it.get<String>("label", String::class),
                                it.get<ObjectBase>("node", person.type.kcls).internalId(),
                            )
                        },
                        nodes.map { it.internalId() },
                        info.get<Boolean>("hasNextPage", Boolean::class) to
                            info.get<Boolean>("hasPreviousPage", Boolean::class),
                    ),
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["members", "linkedMembers"])
    fun `relationship lookup follows existing direct or association storage and projects references`(field: String) =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val person = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(person)
                val kind = if (field == "members") "Membership" else "LabeledMembership"
                repeat(2) { index ->
                    val id = insertMember(f, kind, person, index + 1)
                    if (field == "linkedMembers") {
                        f.linkRecord(field, id, "edge-$index")
                    }
                }
                val lookup = related(f, field).project(reference(f, kind, "person"))
                val key = GlobalID(f.type(f.assignment.typeName), f.assignmentId)
                val connectionName = if (field == "members") "AccessConnection" else "LabeledAccessConnection"
                val selections =
                    if (field == "members") {
                        "edges { cursor node { id } }"
                    } else {
                        "edges { cursor label node { id } }"
                    }
                val page = f.lookupConnection(lookup, key, connectionName, selections, mapOf("last" to 2))
                val edgeKind = if (field == "members") "AccessEdge" else "LabeledAccessEdge"
                val edges = page.get<List<ObjectBase>>("edges", f.reflection(edgeKind + f.suffix).kcls)
                val labels =
                    if (field == "members") {
                        emptySet()
                    } else {
                        edges.map { it.get<String>("label", String::class) }.toSet()
                    }
                assertEquals(
                    Triple(
                        List(2) { person.internalID },
                        List(2) { person.internalID },
                        if (field == "members") emptySet() else setOf("edge-0", "edge-1"),
                    ),
                    Triple(
                        f.lookup(lookup, key).map { (it as ObjectBase).internalId() },
                        edges.map { it.get<ObjectBase>("node", person.type.kcls).internalId() },
                        labels,
                    ),
                )
            }
        }

    @Test
    fun `existing connection reader uses the same generated concrete association names`() =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val person = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(person)
                val id = insertMember(f, "LabeledMembership", person, 1)
                f.linkRecord("linkedMembers", id, "edge-label")
                val connection =
                    f.readConnection(
                        "linkedMembers",
                        "edges { label node { id } }",
                        mapOf("first" to 1),
                    )
                val edge =
                    connection
                        .get<List<ObjectBase>>(
                            "edges",
                            f.reflection("LabeledMembershipEdge${f.suffix}").kcls,
                        ).single()
                assertEquals(
                    id to "edge-label",
                    edge.get<ObjectBase>("node", f.type("LabeledMembership${f.suffix}").kcls).internalId() to
                        edge.get<String>("label", String::class),
                )
            }
        }

    @Test
    fun `missing parent is an empty list and empty connection`() =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val lookup = related(f, "members")
                val key = GlobalID(f.type(f.assignment.typeName), UUID.randomUUID().toString())
                val connection =
                    f.lookupConnection(
                        lookup,
                        key,
                        "MembershipConnection",
                        "edges { cursor }",
                        mapOf("last" to 1),
                    )
                val edges = connection.get<List<ObjectBase>>("edges", f.reflection("MembershipEdge${f.suffix}").kcls)
                assertEquals(
                    emptyList<NodeObject>() to emptyList<ObjectBase>(),
                    f.lookup(lookup, key) to edges,
                )
            }
        }

    @Test
    fun `connection result must match projected node type`() =
        withFixture(modernConnections = true, lookups = true) { f ->
            runBlocking {
                val lookup = related(f, "members")
                assertFailsWith<IllegalArgumentException> {
                    f.lookupConnection(
                        lookup,
                        GlobalID(f.type(f.assignment.typeName), f.assignmentId),
                        "AccessConnection",
                        "edges { cursor }",
                    )
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun reference(
        f: ApprovalRequestFixture,
        kind: String,
        name: String,
    ): CompositeField<NodeObject, NodeObject> = f.field(name, kind + f.suffix) as CompositeField<NodeObject, NodeObject>

    @Suppress("UNCHECKED_CAST")
    private fun related(
        f: ApprovalRequestFixture,
        field: String,
    ): DbLookup<GlobalID<NodeObject>, NodeObject> =
        DbLookup.related(f.field(field) as CompositeField<NodeObject, Connection<Edge<NodeObject>, NodeObject>>)

    private suspend fun insertMember(
        f: ApprovalRequestFixture,
        kind: String,
        person: GlobalID<NodeObject>,
        index: Int,
    ): String {
        val id = UUID(0, index.toLong()).toString()
        val values =
            if (kind == "Membership") {
                PgGraphqlObject.of(
                    "uuidId" to id,
                    "label" to "row",
                    "personId" to person,
                    "reviewAssignment${f.suffix}Id" to f.assignmentId,
                )
            } else {
                PgGraphqlObject.of("uuidId" to id, "label" to "row", "personId" to person)
            }
        f.insertRecord(kind, values)
        return id
    }
}
