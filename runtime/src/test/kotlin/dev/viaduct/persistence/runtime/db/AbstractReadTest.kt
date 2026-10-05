@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AbstractReadTest {
    @Test
    fun `shared interface fields use the concrete nullability policy`() {
        val errors =
            SemanticNotNullValidator(setOf("AbstractPerson.name")).validate(
                SemanticValidationRequest(
                    data = Json.parseToJsonElement("""{"__typename":"AbstractPerson","name":null}""").jsonObject,
                    errors = emptyList(),
                    document = "fragment Main on AbstractActor { name }",
                    rootType = "AbstractActor",
                    rootResponseKey = "actor",
                    schema = PgGraphqlTranslationSchema(emptyMap(), emptyMap()),
                ),
            )
        assertThat(errors.single().message).contains("AbstractPerson.name")
    }

    @Test
    fun `semantic nullability ignores fields on other concrete types`() {
        val validator = SemanticNotNullValidator(setOf("AbstractPerson.name", "AbstractGroup.name"))
        val errors =
            validator.validate(
                SemanticValidationRequest(
                    data = Json.parseToJsonElement("""{"__typename":"AbstractGroup","name":"Guests"}""").jsonObject,
                    errors = emptyList(),
                    document =
                        """
                        fragment Main on AbstractActor {
                          ... on AbstractPerson { alias: name }
                          ... on AbstractGroup { name }
                        }
                        """.trimIndent(),
                    rootType = "AbstractActor",
                    rootResponseKey = "actor",
                    schema =
                        PgGraphqlTranslationSchema(
                            emptyMap(),
                            emptyMap(),
                            abstractTypes = AbstractTypeMappings.load(javaClass.classLoader),
                        ),
                ),
            )
        assertThat(errors).isEqualTo(emptyList())
    }

    @Test
    fun `dbClient uses explicit concrete collection for an abstract root`() =
        runBlocking {
            val selections = selections()
            val client =
                DbClient(
                    HttpClient(
                        MockEngine { request ->
                            val body =
                                Json
                                    .parseToJsonElement(
                                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                                    ).jsonObject
                            assertThat(body.getValue("query").jsonPrimitive.content).contains("on AbstractPerson")
                            respond(
                                """
                                {"data":{"abstractPersonCollection":{"edges":[
                                  {"node":{"__typename":"AbstractPerson","name":"Ada"}}
                                ]}}}
                                """.trimIndent(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
                    "https://example.test/graphql",
                )
            val result =
                client.fetchJsonResult(
                    mockk(),
                    DbRead(
                        DbRoot("abstractPersonCollection", singleViaFilteredCollection = true),
                        AbstractPerson.Reflection,
                    ),
                    selections,
                )
            assertThat(
                result.data,
            ).isEqualTo(Json.parseToJsonElement("""{"__typename":"AbstractPerson","name":"Ada"}"""))
        }

    @Test
    fun `grt conversion narrows the root using Viaduct selectionSetFor`() {
        val abstract = selections()
        val concrete = mockk<SelectionSet<AbstractPerson>>()
        every { abstract.selectionSetFor(AbstractPerson.Reflection) } returns concrete
        val response = Json.parseToJsonElement("""{"__typename":"AbstractPerson","display":"Ada"}""").jsonObject
        assertThat(response.concreteSelections(abstract)).isSameInstanceAs(concrete)
    }

    @Test
    fun `grt conversion rejects unrelated root typename`() {
        val response = Json.parseToJsonElement("""{"__typename":"AbstractActivity"}""").jsonObject
        assertFailsWith<IllegalArgumentException> { response.concreteSelections(selections()) }
    }

    @Test
    fun `grt conversion requires typename for an abstract root`() {
        val response = Json.parseToJsonElement("""{"name":"Ada"}""").jsonObject
        val error = assertFailsWith<IllegalArgumentException> { response.concreteSelections(selections()) }
        assertThat(requireNotNull(error.message)).contains("Missing concrete __typename")
    }

    @Test
    fun `grt conversion rejects an abstract typename`() {
        val response = Json.parseToJsonElement("""{"__typename":"AbstractActor"}""").jsonObject
        val error = assertFailsWith<IllegalArgumentException> { response.concreteSelections(selections()) }
        assertThat(requireNotNull(error.message)).contains("Missing concrete __typename")
    }

    private fun selections(): SelectionSet<AbstractActor> =
        mockk<SelectionSet<AbstractActor>>().also {
            every { it.type } returns AbstractActor.Reflection
            every { it.isEmpty() } returns false
            every { it.toFragment() } returns
                OutputSelectionFragment(
                    "Main",
                    "fragment Main on AbstractActor { name }",
                    emptyMap(),
                )
        }
}
