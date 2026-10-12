@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.DbLookup
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals

/** Observe actual pg_graphql index scans with normal planner settings and a selective fixture. */
class IndexedLookupIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = ["scalar", "nullable", "composite"])
    fun `secondary indexes serve property lookups without requiring unique values`(kind: String) =
        withFixture(modernConnections = true, lookups = true, indexedLookups = true) { f ->
            runBlocking {
                val person = checkNotNull(f.createRequest("AccessRequest", "requestedPermission", "EDITOR"))
                f.assign(person)
                val database = checkNotNull(f.database)
                val indexed = index(database, f.suffix, kind)
                seed(database, indexed.table, f, person)
                val ids = List(2) { UUID.randomUUID().toString() }.toSet()
                ids.forEach { id ->
                    f.insertRecord(
                        "Membership",
                        PgGraphqlObject.of(
                            "uuidId" to id,
                            "personId" to person,
                            "reviewAssignment${f.suffix}Id" to f.assignmentId,
                            "label" to "needle",
                            "code" to "shared",
                            "nullableCode" to null,
                        ),
                    )
                }
                JdbcOperations.execute(database, "ANALYZE ${quoteIdentifier(indexed.table)}")
                val lookup = lookup(f, person, kind)
                val key = if (kind == "scalar") "shared" else null
                val before = scans(database, indexed.name)
                val list = f.lookup(lookup, key).map { (it as ObjectBase).internalId() }.toSet()
                val page =
                    f.lookupConnection(
                        lookup,
                        key,
                        "MembershipConnection",
                        "nodes { id } pageInfo { hasNextPage }",
                        mapOf("first" to 2),
                    )
                val nodes =
                    page
                        .get<List<ObjectBase>>("nodes", f.type("Membership${f.suffix}").kcls)
                        .map { it.internalId() }
                        .toSet()
                val after = scans(database, indexed.name)
                assertEquals(
                    listOf(ids, ids, true, true),
                    listOf(list, nodes, after > before, explain(database, indexed, person, kind)),
                )
            }
        }

    private fun lookup(
        f: ApprovalRequestFixture,
        person: GlobalID<NodeObject>,
        kind: String,
    ): DbLookup<String?, NodeObject> =
        when (kind) {
            "scalar" -> DbLookup.by(f.generatedField("code", "Membership${f.suffix}"))
            "nullable" ->
                DbLookup.where(f.type("Membership${f.suffix}")) { PgGraphqlFilter.isNull("nullableCode") }
            else ->
                DbLookup.where(f.type("Membership${f.suffix}")) {
                    PgGraphqlFilter.allOf(
                        PgGraphqlFilter.eq("personId", person),
                        PgGraphqlFilter.eq("label", "needle"),
                    )
                }
        }

    private fun index(
        database: Connection,
        suffix: String,
        kind: String,
    ): IndexedTable {
        val columns =
            when (kind) {
                "scalar" -> "(code)"
                "nullable" -> "(nullable_code)"
                else -> "(person_id, label)"
            }
        return JdbcOperations.query(
            database,
            "SELECT tablename, indexname FROM pg_indexes WHERE schemaname = 'public' " +
                "AND tablename LIKE ? AND indexdef LIKE ? AND indexdef NOT LIKE 'CREATE UNIQUE%'",
            { result ->
                check(result.next()) { "Generated secondary index missing" }
                IndexedTable(result.getString(1), result.getString(2)).also { check(!result.next()) }
            },
            "%$suffix%",
            "%$columns",
        )
    }

    private fun seed(
        database: Connection,
        table: String,
        f: ApprovalRequestFixture,
        person: GlobalID<NodeObject>,
    ) {
        JdbcOperations.execute(
            database,
            "INSERT INTO ${quoteIdentifier(table)} (_uuid_id, person_id, " +
                "${quoteIdentifier("review_assignment${f.suffix}_id")}, label, code, nullable_code) " +
                "SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'noise', 'bulk-' || n, 'bulk-' || n " +
                "FROM generate_series(1, 8000) n",
            person.internalID,
            f.assignmentId,
        )
    }

    private fun scans(
        database: Connection,
        name: String,
    ): Long {
        JdbcOperations.execute(database, "SELECT pg_stat_force_next_flush()")
        JdbcOperations.execute(database, "SELECT pg_stat_clear_snapshot()")
        return JdbcOperations.query(
            database,
            "SELECT idx_scan FROM pg_stat_all_indexes WHERE indexrelname = ?",
            { result ->
                check(result.next())
                result.getLong(1)
            },
            name,
        )
    }

    private fun explain(
        database: Connection,
        indexed: IndexedTable,
        person: GlobalID<NodeObject>,
        kind: String,
    ): Boolean {
        val condition =
            when (kind) {
                "scalar" -> "code = ?"
                "nullable" -> "nullable_code IS NULL"
                else -> "person_id = ?::uuid AND label = ?"
            }
        val parameters =
            when (kind) {
                "scalar" -> arrayOf("shared")
                "composite" -> arrayOf(person.internalID, "needle")
                else -> emptyArray()
            }
        return JdbcOperations.query(
            database,
            "EXPLAIN SELECT _uuid_id FROM ${quoteIdentifier(indexed.table)} WHERE $condition",
            { result -> buildList { while (result.next()) add(result.getString(1)) }.any { indexed.name in it } },
            *parameters,
        )
    }

    private data class IndexedTable(
        val table: String,
        val name: String,
    )
}
