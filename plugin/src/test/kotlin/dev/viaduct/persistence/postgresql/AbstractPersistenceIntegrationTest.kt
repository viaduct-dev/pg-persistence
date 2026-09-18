package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlFieldCoordinate
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Real pg_graphql queries, using only uniquely named test tables. */
class AbstractPersistenceIntegrationTest {
    @Test
    fun `union reference returns the concrete object`() =
        withDatabase { fixture ->
            assertEquals(
                Json.parseToJsonElement("""{"__typename":"${fixture.person}","name":"Ada"}"""),
                fixture.read().getValue("subject"),
            )
        }

    @Test
    fun `interface reference returns shared fields`() =
        withDatabase { fixture ->
            assertEquals(
                Json.parseToJsonElement("""{"__typename":"${fixture.group}","name":"Guests"}"""),
                fixture.read().getValue("actor"),
            )
        }

    @Test
    fun `mixed list retains both concrete types`() =
        withDatabase { fixture ->
            val nodes = fixture.read().getValue("subjects").jsonArray
            assertEquals(
                setOf(fixture.person, fixture.group),
                nodes
                    .map {
                        it.jsonObject
                            .getValue("__typename")
                            .jsonPrimitive.content
                    }.toSet(),
            )
        }

    @Test
    fun `connection restores public connection and edge type names`() =
        withDatabase { fixture ->
            val result = fixture.read().getValue("connections").jsonObject
            val actual =
                result
                    .getValue("edges")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("__typename")
                            .jsonPrimitive.content to
                            it.jsonObject
                                .getValue("node")
                                .jsonObject
                                .getValue("__typename")
                                .jsonPrimitive.content
                    }.toSet()
            assertEquals(fixture.collection, result.getValue("__typename").jsonPrimitive.content)
            assertEquals(setOf(fixture.edge to fixture.person, fixture.edge to fixture.group), actual)
        }

    @Test
    fun `nodes accessor returns concrete targets without association rows`() =
        withDatabase { fixture ->
            val nodes =
                fixture
                    .read()
                    .getValue("connections")
                    .jsonObject
                    .getValue("nodes")
                    .jsonArray
            assertEquals(
                setOf(fixture.person, fixture.group),
                nodes
                    .map {
                        it.jsonObject
                            .getValue("__typename")
                            .jsonPrimitive.content
                    }.toSet(),
            )
        }

    @Test
    fun `multiple populated targets violate the database constraint`() =
        withDatabase { fixture ->
            val invalid =
                fixture.database.resolve(
                    """mutation { insertInto${fixture.activity}Collection(objects:[{
            subject${fixture.person}Id:"${fixture.personId}",subject${fixture.group}Id:"${fixture.groupId}"
        }]) { affectedCount } }""",
                )
            assertNotNull(invalid["errors"], "The CHECK constraint must reject two targets")
        }

    @Test
    fun `required reference cannot be omitted`() =
        withDatabase { fixture ->
            val invalid =
                fixture.database.resolve(
                    "mutation { insertInto${fixture.activity}Collection(objects:[{}]) { affectedCount } }",
                )
            assertNotNull(invalid["errors"], "The CHECK constraint must require a target")
        }

    @Test
    fun `one cursor paginates across different concrete targets`() =
        withDatabase { fixture ->
            val first =
                fixture
                    .read(
                        "(first: 1)",
                    ).getValue("connections")
                    .jsonObject
                    .getValue("edges")
                    .jsonArray
                    .single()
                    .jsonObject
            val cursor = first.getValue("cursor").jsonPrimitive.content
            val second =
                fixture
                    .read("""(first: 1, after: "$cursor")""")
                    .getValue("connections")
                    .jsonObject
                    .getValue("edges")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                setOf(fixture.person, fixture.group),
                listOf(first, second)
                    .map {
                        it
                            .getValue("node")
                            .jsonObject
                            .getValue("__typename")
                            .jsonPrimitive.content
                    }.toSet(),
            )
        }

    @Test
    fun `custom physical foreign key names preserve pg_graphql inputs`() =
        withDatabase(AbstractTestNamingStrategy::class.java.name) { fixture ->
            assertEquals(
                "Guests",
                fixture
                    .read()
                    .getValue("actor")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
        }

    private fun withDatabase(
        namingStrategy: String = ViaductPhysicalNamingStrategy::class.java.name,
        test: (AbstractDatabaseFixture) -> Unit,
    ) {
        val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
        val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
        val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
        val connection = runCatching { DriverManager.getConnection(url, user, password) }.getOrNull()
        assumeTrue(connection != null, "Local PostgreSQL is unavailable")
        requireNotNull(connection).use { database ->
            val fixture = AbstractDatabaseFixture(database, namingStrategy)
            fixture.withSchema(
                mapOf(
                    "hibernate.connection.url" to url,
                    "hibernate.connection.username" to user,
                    "hibernate.connection.password" to password,
                    "hibernate.connection.driver_class" to "org.postgresql.Driver",
                    "hibernate.boot.allow_jdbc_metadata_access" to "true",
                ),
            ) { test(fixture) }
        }
    }
}

private class AbstractDatabaseFixture(
    val database: Connection,
    private val namingStrategy: String,
) {
    private val suffix =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
    val person = "Person$suffix"
    val group = "Group$suffix"
    val activity = "Activity$suffix"
    private val subject = "Subject$suffix"
    private val actor = "Actor$suffix"
    val edge = "SubjectEdge$suffix"
    val collection = "SubjectConnection$suffix"
    val personId: String = UUID.randomUUID().toString()
    val groupId: String = UUID.randomUUID().toString()
    private var modelValue: PersistenceModel? = null
    private val model: PersistenceModel get() = checkNotNull(modelValue)

    fun withSchema(
        settings: Map<String, String>,
        test: () -> Unit,
    ) {
        withGeneratedDatabase(database, graphql(), settings, namingStrategy) { generatedModel, _, _ ->
            modelValue = generatedModel
            populate()
            test()
        }
    }

    private fun populate() {
        database.insert(person, """uuidId:"$personId",name:"Ada"""")
        database.insert(group, """uuidId:"$groupId",name:"Guests"""")
        val ownerId = database.insert(activity, """subject${person}Id:"$personId",actor${group}Id:"$groupId"""")
        model.abstractTypes.relationships.filter { it.collection }.forEach { relationship ->
            database.insert(relationship.rowType, """ownerId:"$ownerId",node${person}Id:"$personId"""")
            database.insert(relationship.rowType, """ownerId:"$ownerId",node${group}Id:"$groupId"""")
        }
    }

    fun read(connectionArguments: String = ""): JsonObject {
        val translation =
            PgGraphqlTranslationSchema(
                emptyMap(),
                mapOf(
                    PgGraphqlFieldCoordinate(activity, "connections") to collection,
                    PgGraphqlFieldCoordinate(collection, "edges") to edge,
                    PgGraphqlFieldCoordinate(edge, "node") to subject,
                ),
                abstractTypes = model.abstractTypes,
            )
        val fragment =
            PgGraphqlTranslation.translateSelectionDocument(
                """
                    fragment Main on $activity {
                      subject { ... on $person { name } }
                      actor { name }
                      subjects { ... on $actor { name } }
                connections$connectionArguments { __typename nodes { ... on $actor { name } } edges {
                    __typename ... @include(if: true) { cursor label node { ... on $actor { name } } }
                  } }
                    }
                """.trimIndent(),
                translation,
            )
        val field = activity.replaceFirstChar(Char::lowercaseChar) + "Collection"
        val query = PgGraphqlTranslation.buildRootQuery(field, "", "", fragment, true)
        val raw = database.resolve(query)
        assertNull(raw["errors"], raw.toString() + "\n" + query)
        val restored = PgGraphqlTranslation.restoreViaductResponseShape(raw.getValue("data")).jsonObject
        return restored
            .getValue(field)
            .jsonObject
            .getValue("edges")
            .jsonArray
            .single()
            .jsonObject
            .getValue("node")
            .jsonObject
    }

    private fun graphql() =
        """
        interface Node { id: ID! }
        interface $actor { name: String! }
        type $person implements Node & $actor { id: ID!, name: String! }
        type $group implements Node & $actor { id: ID!, name: String! }
        union $subject = $person | $group
        type $edge { cursor: String!, node: $subject!, label: String }
        type $collection { edges: [$edge!]!, nodes: [$subject!]! }
        type $activity implements Node {
          id: ID!
          subject: $subject!
          actor: $actor
          subjects: [$subject!]!
          connections(first: Int, after: String): $collection!
        }
        """.trimIndent()
}

private fun Connection.insert(
    type: String,
    values: String,
): String {
    val result = resolve("mutation { insertInto${type}Collection(objects:[{$values}]) { records { uuidId } } }")
    assertNull(result["errors"], result.toString())
    return result
        .getValue("data")
        .jsonObject.values
        .single()
        .jsonObject
        .getValue("records")
        .jsonArray
        .single()
        .jsonObject
        .getValue("uuidId")
        .jsonPrimitive.content
}

private fun Connection.resolve(query: String): JsonObject =
    JdbcOperations.query(this, "SELECT graphql.resolve(?)", { result ->
        check(result.next())
        Json.parseToJsonElement(result.getString(1)).jsonObject
    }, query)

class AbstractTestNamingStrategy : ViaductPhysicalNamingStrategy() {
    override fun toPhysicalColumnName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier {
        val standard = super.toPhysicalColumnName(logicalName, jdbcEnvironment)
        return if (logicalName.text.endsWith("Id") && logicalName.text != "internalId") {
            Identifier.toIdentifier("custom_" + standard.text, standard.isQuoted)
        } else {
            standard
        }
    }
}
