package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.jdbc.JdbcOperations
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import kotlin.test.assertEquals

/** Exercises schema-generated mappings on PostgreSQL with ordinary Hibernate dynamic entities. */
class ReverseCollectionIntegrationTest {
    @ParameterizedTest
    @CsvSource("owner,true", "owner,false", "groupId,true", "groupId,false")
    fun `reverse FK collection follows insert move nullability and delete`(
        field: String,
        nullable: Boolean,
    ) {
        withFixture(field, nullable) { factory, groupType, memberType ->
            val ids =
                factory.fromTransaction { session ->
                    val first =
                        mutableMapOf<String, Any?>(
                            "internalId" to UUID.randomUUID(),
                            "name" to "first",
                            "members" to arrayListOf<Any>(),
                        )
                    val second =
                        mutableMapOf<String, Any?>(
                            "internalId" to UUID.randomUUID(),
                            "name" to "second",
                            "members" to arrayListOf<Any>(),
                        )
                    session.persist(groupType, first)
                    session.persist(groupType, second)
                    val member =
                        mutableMapOf<String, Any?>(
                            "internalId" to UUID.randomUUID(),
                            "label" to "member",
                            field to first,
                        )
                    session.persist(memberType, member)
                    Triple(session.getIdentifier(first), session.getIdentifier(second), session.getIdentifier(member))
                }
            val observed =
                mutableListOf(members(factory, groupType, ids.first), members(factory, groupType, ids.second))
            factory.fromTransaction { session ->
                entity(session, memberType, ids.third)[field] = session.getReference(groupType, ids.second)
            }
            observed += members(factory, groupType, ids.first)
            observed += members(factory, groupType, ids.second)
            val rejected =
                runCatching {
                    factory.fromTransaction { session -> entity(session, memberType, ids.third)[field] = null }
                }.isFailure
            observed += members(factory, groupType, ids.second)
            factory.fromTransaction { session -> session.remove(entity(session, memberType, ids.third)) }
            observed += members(factory, groupType, ids.second)
            assertEquals(
                listOf(
                    listOf(
                        listOf("member"),
                        emptyList(),
                        emptyList(),
                        listOf("member"),
                        if (nullable) emptyList() else listOf("member"),
                        emptyList(),
                    ),
                    !nullable,
                ),
                listOf(observed, rejected),
            )
        }
    }

    private fun members(
        factory: SessionFactory,
        type: String,
        id: Any,
    ): List<String> =
        factory.fromTransaction { session ->
            (entity(session, type, id)["members"] as Iterable<*>).map { (it as Map<*, *>)["label"] as String }
        }

    @Suppress("UNCHECKED_CAST")
    private fun entity(
        session: Session,
        type: String,
        id: Any,
    ): MutableMap<String, Any?> = session.find(type, id) as MutableMap<String, Any?>

    private fun withFixture(
        field: String,
        nullable: Boolean,
        test: (SessionFactory, String, String) -> Unit,
    ) {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
        val group = "ReverseGroup$suffix"
        val member = "ReverseMember$suffix"
        val required = if (nullable) "" else "!"
        val owner = if (field == "owner") "owner: $group$required" else "$field: ID$required @idOf(type: \"$group\")"
        val sdl =
            """
            interface Node { id: ID! }
            directive @idOf(type: String!) on FIELD_DEFINITION
            type $group implements Node { id: ID!, name: String!, members: [$member!]! }
            type $member implements Node { id: ID!, label: String!, $owner }
            """.trimIndent()
        val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
        require(listOf("jdbc:postgresql://127.0.0.1:", "jdbc:postgresql://localhost:").any(url::startsWith))
        val settings =
            mapOf(
                "hibernate.connection.url" to url,
                "hibernate.connection.username" to (System.getenv("PG_INTEGRATION_USER") ?: "postgres"),
                "hibernate.connection.password" to (System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"),
                "hibernate.connection.driver_class" to "org.postgresql.Driver",
                "hibernate.boot.allow_jdbc_metadata_access" to "true",
            )
        withGeneratedModel(sdl, settings) { model, _, _, handle ->
            val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
            handle.metadata.buildSessionFactory().use { factory ->
                try {
                    factory.schemaManager.exportMappedObjects(false)
                    factory.fromTransaction { session ->
                        session.doWork { JdbcOperations.execute(it, PostgresqlOverlay.renderMigration(effective)) }
                    }
                    test(factory, group, member)
                } finally {
                    factory.schemaManager.dropMappedObjects(false)
                }
            }
        }
    }
}
