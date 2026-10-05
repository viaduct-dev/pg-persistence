package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.db.toPgGraphqlJsonElement
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import graphql.GraphQL
import graphql.parser.Parser
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeRuntimeWiring
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.Validator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssociationTranslationValidationTest {
    @Test
    fun `nested abstract selection on a concrete edge field translates once`() {
        validate(
            """
            fragment Main on Activity { subjects { edges { createdBy { ...Favorite } } } }
            fragment Favorite on Person { favorite { ... on Person { name } ... on Group { name } } }
            """,
        )
    }

    @Test
    fun `connection and edge fragments do not retranslate nested relationships`() {
        validate(
            """
            fragment Main on Activity { subjects { ...Connection } }
            fragment Connection on SubjectConnection { edges { ...Edge } }
            fragment Edge on SubjectEdge { createdBy { favorite { ... on Person { name } } } }
            """,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["subjects", "members"])
    fun `cursor alias cannot collide with the generated row`(field: String) {
        validate("fragment Main on Activity { $field { edges { node: cursor label } } }")
    }

    @ParameterizedTest
    @ValueSource(strings = ["subjects", "members"])
    fun `named edge fragments keep cursor fields on the backend edge`(field: String) {
        val edge = if (field == "subjects") "SubjectEdge" else "PersonEdge"
        validate(
            """
            fragment Main on Activity { $field { edges { ...Edge @include(if: true) } } }
            fragment Edge on $edge { cursor label }
            """,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["subjects", "members"])
    fun `aliased cursor and row fields restore without losing either value`(field: String) {
        assertEquals(
            Json.parseToJsonElement("""{"edges":[{"node":"c1","label":"member"}]}"""),
            execute(field, "edges { node: cursor label }"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["subjects", "members"])
    fun `fragment directives govern both cursor and row fields`(field: String) {
        val edge = if (field == "subjects") "SubjectEdge" else "PersonEdge"
        assertEquals(
            Json.parseToJsonElement("""{"edges":[{"cursor":"c1"}]}"""),
            execute(field, "edges { cursor ... on $edge @skip(if: true) { hidden: cursor label } }"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["subjects", "members"])
    fun `multiple aliases for the target node are all restored`(field: String) {
        val selection = if (field == "subjects") "... on Person { name }" else "name"
        // Abstract targets include the concrete typename used by JSON-to-GRT conversion.
        val node = if (field == "subjects") """{"name":"Ada","__typename":"Person"}""" else """{"name":"Ada"}"""
        assertEquals(
            Json.parseToJsonElement("""{"edges":[{"first":$node,"second":$node}]}"""),
            execute(field, "edges { first: node { $selection } second: node { $selection } }"),
        )
    }

    @Test
    fun `error paths remove the reserved row alias but preserve the public cursor alias`() {
        val paths =
            listOf(
                listOf("${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}edges", 0, VIADUCT_ASSOCIATION_ROW_ALIAS, "label"),
                listOf("${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}edges", 0, "node"),
                listOf(
                    "${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}edges",
                    0,
                    VIADUCT_ASSOCIATION_ROW_ALIAS,
                    "${VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX}person",
                    "name",
                ),
                listOf(
                    "${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}edges",
                    0,
                    VIADUCT_ASSOCIATION_ROW_ALIAS,
                    abstractAlias("person", "Person"),
                    "name",
                ),
            )
        assertEquals(
            listOf(
                listOf("edges", "0", "label"),
                listOf("edges", "0", "node"),
                listOf("edges", "0", "person", "name"),
                listOf("edges", "0", "person", "name"),
            ),
            paths.map { path ->
                PgGraphqlTranslation
                    .restoreViaductResponsePath(
                        path.map {
                            if (it is Int) JsonPrimitive(it) else JsonPrimitive(it.toString())
                        },
                    ).map { it.toString().trim('"') }
            },
        )
    }

    @Test
    fun `the generated row alias is rejected in authored selections`() {
        assertFailsWith<IllegalArgumentException> {
            translate("fragment Main on Activity { members { edges { $VIADUCT_ASSOCIATION_ROW_ALIAS: cursor } } }")
        }
    }

    @Test
    fun `cycles through nested fields fail during expansion`() {
        assertFailsWith<IllegalArgumentException> {
            translate("fragment Main on Activity { subjects { edges { createdBy { ...Main } } } }")
        }
    }

    private fun validate(fragment: String) {
        val publicQuery = Parser().parseDocument("query { activity { ...Main } } $fragment")
        assertEquals(emptyList(), Validator().validateDocument(publicSchema, publicQuery, Locale.ROOT))
        val backendQuery = Parser().parseDocument("query { activity { ...Main } } ${translate(fragment)}")
        assertEquals(emptyList(), Validator().validateDocument(backendSchema, backendQuery, Locale.ROOT))
    }

    private fun translate(fragment: String): String =
        PgGraphqlTranslation.translateSelectionDocument(
            document = fragment,
            schema = translationSchema,
        )

    private fun execute(
        field: String,
        selection: String,
    ): JsonElement {
        val fragment = "fragment Main on Activity { $field { $selection } }"
        validate(fragment)
        val query = "query { activity { ...Main } } ${translate(fragment)}"
        val result = GraphQL.newGraphQL(backendSchema).build().execute(query)
        assertEquals(emptyList(), result.errors)
        val json = result.getData<Any>().toPgGraphqlJsonElement()
        return PgGraphqlTranslation
            .restoreViaductResponseShape(json)
            .jsonObject
            .getValue("activity")
            .jsonObject
            .getValue(field)
    }

    private val publicSdl =
        """
        type Query { activity: Activity }
        type Activity { subjects: SubjectConnection members: PersonConnection }
        type SubjectConnection { edges: [SubjectEdge] }
        type PersonConnection { edges: [PersonEdge] }
        type SubjectEdge { cursor: String node: Subject createdBy: Person label: String }
        type PersonEdge { cursor: String node: Person createdBy: Person label: String }
        union Subject = Person | Group
        type Person { name: String favorite: Subject }
        type Group { name: String }
        """.trimIndent()

    private val publicRegistry = SchemaParser().parse(publicSdl)
    private val publicSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(publicRegistry)
    private val translationSchema =
        PgGraphqlTranslationSchema(
            emptyMap(),
            publicSchema.allTypesAsList
                .filterIsInstance<GraphQLObjectType>()
                .flatMap { type ->
                    type.fieldDefinitions.map { field ->
                        PgGraphqlFieldCoordinate(type.name, field.name) to
                            (GraphQLTypeUtil.unwrapAll(field.type) as GraphQLNamedType).name
                    }
                }.toMap(),
            setOf(PgGraphqlFieldCoordinate("Activity", "members")),
            AbstractTypeMappings(
                possibleTypes = mapOf("Subject" to setOf("Person", "Group")),
                relationships =
                    listOf(
                        AbstractRelationship(
                            "Activity",
                            "subjects",
                            "Subject",
                            setOf("Person", "Group"),
                            false,
                            collection = true,
                            connectionType = "SubjectConnection",
                            edgeType = "SubjectEdge",
                        ),
                        AbstractRelationship("Person", "favorite", "Subject", setOf("Person", "Group"), true),
                    ),
            ),
        )

    private val backendSdl =
        """
        type Query { activity: Activity }
        type Activity { subjects: SubjectRowConnection membersAssociations: PersonRowConnection }
        type SubjectRowConnection { edges: [SubjectRowEdge] }
        type PersonRowConnection { edges: [PersonRowEdge] }
        type SubjectRowEdge { cursor: String node: SubjectRow }
        type PersonRowEdge { cursor: String node: PersonRow }
        type SubjectRow { nodePerson: Person nodeGroup: Group createdBy: Person label: String }
        type PersonRow { node: Person createdBy: Person label: String }
        type Person { name: String favoritePerson: Person favoriteGroup: Group }
        type Group { name: String }
        """.trimIndent()

    private val backendSchema =
        SchemaGenerator().makeExecutableSchema(
            SchemaParser().parse(backendSdl),
            RuntimeWiring
                .newRuntimeWiring()
                .type(
                    TypeRuntimeWiring.newTypeWiring("Query").dataFetcher("activity") {
                        val person = mapOf("name" to "Ada")

                        fun connection(target: String) =
                            mapOf(
                                "edges" to
                                    listOf(
                                        mapOf("cursor" to "c1", "node" to mapOf("label" to "member", target to person)),
                                    ),
                            )
                        mapOf("subjects" to connection("nodePerson"), "membersAssociations" to connection("node"))
                    },
                ).build(),
        )
}
