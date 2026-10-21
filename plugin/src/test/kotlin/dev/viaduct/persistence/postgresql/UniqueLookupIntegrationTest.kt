@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.DbLookup
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.CompositeField
import viaduct.api.types.NodeObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Unique constraints are generated from pg-persistence.yaml and queried through real graphql.resolve. */
class UniqueLookupIntegrationTest {
    @ParameterizedTest
    @CsvSource("code,true", "code,false", "nullableCode,true", "nullableCode,false")
    fun `unique scalar lookups return one match or no matches`(
        field: String,
        exists: Boolean,
    ) = withUniqueFixture { f ->
        runBlocking {
            val person = owner(f)
            val id = insert(f, person, "label-a", "key-a", "key-a")
            insert(f, person, "label-b", "key-b", "key-b")
            val lookup = DbLookup.by<String, NodeObject>(f.generatedField(field, "Membership${f.suffix}"))
            assertEquals(
                expected(if (exists) listOf(id) else emptyList()),
                results(f, lookup, if (exists) "key-a" else "absent"),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["match", "different-person", "different-label"])
    fun `complete composite keys match both the foreign key and scalar`(case: String) =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                val outsider = f.createRequest("AccessRequest", "requestedPermission", "VIEWER")
                val absentPerson = f.createRequest("AccessRequest", "requestedPermission", "VIEWER")
                val id = insert(f, person, "label-a", "key-a")
                insert(f, person, "label-b", "key-b")
                insert(f, outsider, "label-a", "key-c")
                val lookup =
                    DbLookup.where<MemberKey, NodeObject>(f.type("Membership${f.suffix}")) { key ->
                        PgGraphqlFilter.allOf(
                            PgGraphqlFilter.eq("personId", key.person),
                            PgGraphqlFilter.eq("label", key.label),
                        )
                    }
                val key =
                    MemberKey(
                        if (case == "different-person") absentPerson else person,
                        if (case == "different-label") "absent" else "label-a",
                    )
                assertEquals(expected(if (case == "match") listOf(id) else emptyList()), results(f, lookup, key))
            }
        }

    @Test
    fun `matching part of a composite key still returns multiple rows`() =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                val ids = listOf(insert(f, person, "label-a", "key-a"), insert(f, person, "label-b", "key-b"))

                @Suppress("UNCHECKED_CAST")
                val field = f.field("person", "Membership${f.suffix}") as CompositeField<NodeObject, NodeObject>
                assertEquals(expected(ids), results(f, DbLookup.by(field), person))
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `unique foreign keys accept typed IDs and return at most one row`(exists: Boolean) =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                val missing = f.createRequest("AccessRequest", "requestedPermission", "VIEWER")
                val id = UUID.randomUUID().toString()
                f.insertRecord(
                    "LabeledMembership",
                    PgGraphqlObject.of("uuidId" to id, "personId" to person, "label" to "row"),
                )
                @Suppress("UNCHECKED_CAST")
                val field = f.field("person", "LabeledMembership${f.suffix}") as CompositeField<NodeObject, NodeObject>
                assertEquals(
                    expected(if (exists) listOf(id) else emptyList()),
                    results(f, DbLookup.by(field), if (exists) person else missing, "LabeledMembership"),
                )
            }
        }

    @Test
    fun `unique foreign key fixture rejects duplicate references`() =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                f.insertRecord("LabeledMembership", PgGraphqlObject.of("personId" to person, "label" to "first"))
                assertFailsWith<UpstreamGraphqlException> {
                    f.insertRecord("LabeledMembership", PgGraphqlObject.of("personId" to person, "label" to "second"))
                }
            }
        }

    @Test
    fun `nullable unique keys preserve every null match`() =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                val ids = listOf(insert(f, person, "label-a", "key-a"), insert(f, person, "label-b", "key-b"))
                insert(f, person, "label-c", "key-c", "not-null")
                val lookup =
                    DbLookup.where<Unit, NodeObject>(f.type("Membership${f.suffix}")) {
                        PgGraphqlFilter.isNull("nullableCode")
                    }
                assertEquals(expected(ids), results(f, lookup, Unit))
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `generated id lookups match the persisted primary key`(exists: Boolean) =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                val id = insert(f, person, "label-a", "key-a")
                val lookup =
                    DbLookup.by<GlobalID<NodeObject>, NodeObject>(f.generatedField("id", "Membership${f.suffix}"))
                val key = GlobalID(f.type("Membership${f.suffix}"), if (exists) id else UUID.randomUUID().toString())
                assertEquals(expected(if (exists) listOf(id) else emptyList()), results(f, lookup, key))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["code", "composite", "nullableCode"])
    fun `lookup fixtures enforce each generated unique constraint`(constraint: String) =
        withUniqueFixture { f ->
            runBlocking {
                val person = owner(f)
                insert(f, person, "label-a", "key-a", "nullable-a")
                assertFailsWith<UpstreamGraphqlException> {
                    insert(
                        f,
                        person,
                        if (constraint == "composite") "label-a" else "label-b",
                        if (constraint == "code") "key-a" else "key-b",
                        if (constraint == "nullableCode") "nullable-a" else "nullable-b",
                    )
                }
            }
        }

    private fun withUniqueFixture(test: (ApprovalRequestFixture) -> Unit) =
        withFixture(modernConnections = true, lookups = true, uniqueLookups = true, test = test)

    private suspend fun owner(f: ApprovalRequestFixture): GlobalID<NodeObject> {
        val person = checkNotNull(f.createRequest("AccessRequest", "requestedPermission", "EDITOR"))
        f.assign(person)
        return person
    }

    private suspend fun insert(
        f: ApprovalRequestFixture,
        person: GlobalID<NodeObject>,
        label: String,
        code: String,
        nullableCode: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        f.insertRecord(
            "Membership",
            PgGraphqlObject.of(
                "uuidId" to id,
                "personId" to person,
                "reviewAssignment${f.suffix}Id" to f.assignmentId,
                "label" to label,
                "code" to code,
                "nullableCode" to nullableCode,
            ),
        )
        return id
    }

    private suspend fun <K> results(
        f: ApprovalRequestFixture,
        lookup: DbLookup<K, NodeObject>,
        key: K,
        kind: String = "Membership",
    ): LookupResults {
        val list = f.lookup(lookup, key).map { (it as ObjectBase).internalId() }.sorted()
        val pages =
            listOf("first", "last").map { direction ->
                val connection =
                    f.lookupConnection(
                        lookup,
                        key,
                        "${kind}Connection",
                        "edges { node { id } } pageInfo { hasNextPage hasPreviousPage }",
                        mapOf(direction to 2),
                    )
                val nodes =
                    connection.get<List<ObjectBase>>("edges", f.reflection("${kind}Edge${f.suffix}").kcls).map {
                        it.get<ObjectBase>("node", f.type(kind + f.suffix).kcls).internalId()
                    }
                val info = connection.get<ObjectBase>("pageInfo", f.reflection("PageInfo").kcls)
                PageResults(
                    nodes.sorted(),
                    info.get("hasNextPage", Boolean::class),
                    info.get("hasPreviousPage", Boolean::class),
                )
            }
        return LookupResults(list, pages)
    }

    private fun expected(ids: List<String>): LookupResults =
        LookupResults(ids.sorted(), List(2) { PageResults(ids.sorted(), hasNextPage = false, hasPreviousPage = false) })

    private data class MemberKey(
        val person: GlobalID<NodeObject>,
        val label: String,
    )

    private data class LookupResults(
        val list: List<String>,
        val pages: List<PageResults>,
    )

    private data class PageResults(
        val ids: List<String>,
        val hasNextPage: Boolean,
        val hasPreviousPage: Boolean,
    )
}
