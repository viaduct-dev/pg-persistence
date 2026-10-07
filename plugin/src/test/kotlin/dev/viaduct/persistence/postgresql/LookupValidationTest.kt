@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.runtime.db.DbLookup
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.Connection
import viaduct.api.types.Edge
import viaduct.api.types.NodeObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Exercises excluded types and stale mappings with generated GRTs, without a database. */
class LookupValidationTest {
    @ParameterizedTest
    @ValueSource(strings = ["scalar", "reference", "projection", "source", "relationship"])
    fun `nonpersisted fields and types fail when declaring a lookup`(case: String) =
        withModel { f ->
            assertFailsWith<IllegalArgumentException> {
                when (case) {
                    "scalar" -> DbLookup.by<String, NodeObject>(f.field("Person", "computedName"))
                    "reference" -> DbLookup.by(f.reference("Person", "computedParent"))
                    "projection" ->
                        DbLookup.by<String, NodeObject>(f.field("Person", "name")).project(
                            f.reference("Person", "computedParent"),
                        )
                    "source" -> DbLookup.where<String, NodeObject>(f.type("External")) { PgGraphqlFilter.empty() }
                    else -> DbLookup.related(f.connection("External", "children"))
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["scalar", "id", "reference", "idOfAlias", "idOf", "connection", "projection"])
    fun `stored fields remain valid including resolver connections and idOf aliases`(case: String) =
        withModel { f ->
            assertNotNull(
                when (case) {
                    "scalar" -> DbLookup.by<String, NodeObject>(f.field("Person", "name"))
                    "id" -> DbLookup.by<String, NodeObject>(f.field("Person", "id"))
                    "reference" -> DbLookup.by(f.reference("Person", "parent"))
                    "idOfAlias" -> DbLookup.by<String, NodeObject>(f.field("Person", "parentId"))
                    "idOf" -> DbLookup.by<String, NodeObject>(f.field("Person", "sponsorId"))
                    "connection" -> DbLookup.related(f.connection("Person", "children"))
                    else ->
                        DbLookup.by<String, NodeObject>(f.field("Person", "name")).project(
                            f.reference("Person", "parent"),
                        )
                },
            )
        }

    @Test
    fun `applications without mapping metadata keep provider validation`() =
        withModel(mapping = null) { f ->
            assertNotNull(DbLookup.by<String, NodeObject>(f.field("Person", "computedName")))
        }

    @Test
    fun `validation is scoped to the generated type classloader`() {
        val outcomes = mutableListOf<Boolean>()
        listOf(true, false).forEach { storeName ->
            withModel(storeName = storeName) { f ->
                outcomes += runCatching { DbLookup.by<String, NodeObject>(f.field("Person", "name")) }.isSuccess
            }
        }
        assertEquals(listOf(true, false), outcomes)
    }

    @Test
    fun `custom mapping doctypes do not load external XML resources`() =
        withModel(
            mapping =
                """
                <!DOCTYPE hibernate-mapping SYSTEM "file:///no-such-mapping.dtd">
                <hibernate-mapping>
                  <class entity-name="Person"><property name="name"/></class>
                </hibernate-mapping>
                """.trimIndent(),
        ) { f ->
            assertNotNull(DbLookup.by<String, NodeObject>(f.field("Person", "name")))
        }

    private fun withModel(
        storeName: Boolean = true,
        mapping: String? = "generated",
        test: (Fixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("lookup-validation-").toFile()
        try {
            val schemaDirectory = directory.resolve("schema").apply { check(mkdirs()) }
            // Generate the mapping from an older schema, then add fields only to its GRTs.
            // Normal schema generation already rejects resolver-only fields on persisted nodes.
            val storedSchema =
                SCHEMA
                    .replace("computedName: String @resolver", "")
                    .replace("computedParent: Person @resolver", "")
                    .let { if (storeName) it else it.replace("name: String!", "") }
            val schemaFile = schemaDirectory.resolve("Model.graphqls").apply { writeText(storedSchema) }
            val policy =
                directory.resolve("pg-persistence.yaml").apply {
                    writeText(
                        """
                        types:
                          External:
                            excluded: true
                          Person:
                            fields:
                              children:
                                relationship:
                                  inverseField: parent
                        """.trimIndent(),
                    )
                }
            val model = PersistenceSchemaModelLoader.build(schemaDirectory, policy)
            val generated = directory.resolve("generated")
            HibernateSchemaModelWriter().write(model, generated)
            val resource = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml")
            when (mapping) {
                null -> check(resource.delete())
                "generated" -> Unit
                else -> resource.writeText(mapping)
            }
            schemaFile.writeText(SCHEMA)
            ApprovalRequestFixture.withGrts(schemaFile, generated) { test(Fixture(it)) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        private val loader: ClassLoader,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun type(name: String): Type<NodeObject> =
            loader.loadClass("$PACKAGE.$name\$Reflection").getField("INSTANCE").get(null) as Type<NodeObject>

        @Suppress("UNCHECKED_CAST")
        fun field(
            type: String,
            name: String,
        ): Field<NodeObject> =
            loader.loadClass("$PACKAGE.$type\$Fields").let { fields ->
                fields
                    .getMethod("get" + name.replaceFirstChar(Char::uppercaseChar))
                    .invoke(fields.getField("INSTANCE").get(null)) as Field<NodeObject>
            }

        @Suppress("UNCHECKED_CAST")
        fun reference(
            type: String,
            name: String,
        ): CompositeField<NodeObject, NodeObject> = field(type, name) as CompositeField<NodeObject, NodeObject>

        @Suppress("UNCHECKED_CAST")
        fun connection(
            type: String,
            name: String,
        ): CompositeField<NodeObject, Connection<Edge<NodeObject>, NodeObject>> =
            field(type, name) as CompositeField<NodeObject, Connection<Edge<NodeObject>, NodeObject>>
    }

    companion object {
        private const val PACKAGE = "dev.viaduct.persistence.approvalfixture"
        private val SCHEMA =
            """
            interface Node { id: ID! }
            type Person implements Node {
              id: ID!, name: String!, computedName: String @resolver
              parent: Person, parentId: ID @idOf(type: "Person"), sponsorId: ID @idOf(type: "Person")
              computedParent: Person @resolver
              children(first: Int, after: String, last: Int, before: String): PersonConnection! @resolver
            }
            type External implements Node {
              id: ID!, name: String!
              children(first: Int, after: String, last: Int, before: String): PersonConnection! @resolver
            }
            type PersonEdge @edge { cursor: String!, node: Person! }
            type PersonConnection @connection { edges: [PersonEdge!]!, pageInfo: PageInfo! }
            type PageInfo {
              hasNextPage: Boolean!, hasPreviousPage: Boolean!, startCursor: String, endCursor: String
            }
            directive @idOf(type: String!) on FIELD_DEFINITION
            directive @resolver(isSelective: Boolean, isBatching: Boolean) on OBJECT | FIELD_DEFINITION
            directive @edge on OBJECT
            directive @connection on OBJECT
            """.trimIndent()
    }
}
