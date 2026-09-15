@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.types.CompositeOutput
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AbstractMutationTest {
    @Test
    fun `concrete insert builds a union-valued field`() {
        val plan =
            MutationPayloadPlan.create(
                SubjectPayload.Reflection,
                AbstractPerson.Reflection,
                null,
                null,
                false,
                false,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, SubjectPayload>>()
        val person = references(ctx)
        assertThat(plan.prepare(ctx, AbstractPerson.Reflection)(records)).subjectEquals(person)
    }

    @Test
    fun `concrete insert builds an interface-valued field`() {
        val plan =
            MutationPayloadPlan.create(
                ActorPayload.Reflection,
                AbstractPerson.Reflection,
                null,
                null,
                false,
                false,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, ActorPayload>>()
        val person = references(ctx)
        assertThat(
            plan.prepare(ctx, AbstractPerson.Reflection)(records).actor,
        ).isSameInstanceAs(person)
    }

    @Test
    fun `batch builds a list of abstract node references`() {
        val plan =
            MutationPayloadPlan.create(
                BatchSubjectPayload.Reflection,
                AbstractPerson.Reflection,
                null,
                null,
                true,
                false,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, BatchSubjectPayload>>()
        val person = references(ctx)
        assertThat(
            plan.prepare(ctx, AbstractPerson.Reflection)(records).subjects,
        ).isEqualTo(listOf(person))
    }

    @Test
    fun `sole compatible concrete payload is selected`() {
        val mappings = AbstractTypeMappings(possibleTypes = mapOf("AbstractPayload" to setOf("SubjectPayload")))
        val plan =
            MutationPayloadPlan.create(
                AbstractPayload.Reflection,
                AbstractPerson.Reflection,
                null,
                null,
                false,
                false,
                mappings,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, AbstractPayload>>()
        val person = references(ctx)
        assertThat(
            (plan.prepare(ctx, AbstractPerson.Reflection)(records) as SubjectPayload).subject,
        ).isSameInstanceAs(person)
    }

    @Test
    fun `explicit payload resolves ambiguous abstract return type`() {
        val plan =
            MutationPayloadPlan.create(
                AbstractPayload.Reflection,
                AbstractPerson.Reflection,
                SubjectPayload.Reflection,
                null,
                false,
                false,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, AbstractPayload>>()
        val person = references(ctx)
        assertThat(
            (plan.prepare(ctx, AbstractPerson.Reflection)(records) as SubjectPayload).subject,
        ).isSameInstanceAs(person)
    }

    @Test
    fun `explicit field resolves two compatible entity fields`() {
        val plan =
            MutationPayloadPlan.create(
                AmbiguousSubjectPayload.Reflection,
                AbstractPerson.Reflection,
                null,
                "other",
                false,
                false,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, AmbiguousSubjectPayload>>()
        val person = references(ctx)
        assertThat(
            plan.prepare(ctx, AbstractPerson.Reflection)(records).other,
        ).isSameInstanceAs(person)
    }

    @Test
    fun `ambiguous payload fails before any HTTP write`() =
        runBlocking {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    noWritesClient().entity<AbstractPerson>().insert(
                        AbstractMutationContext(mockk()),
                        PgGraphqlObject.of(),
                    )
                }
            assertThat(requireNotNull(error.message)).contains("Pass payloadType explicitly")
        }

    @Test
    fun `ambiguous field fails before any HTTP write`() =
        runBlocking {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    noWritesClient().entity<AbstractPerson>().insert(
                        AmbiguousMutationContext(mockk()),
                        PgGraphqlObject.of(),
                    )
                }
            assertThat(requireNotNull(error.message)).contains("Pass entityField explicitly")
        }

    @Test
    fun `batch rejects a singular field before building a payload`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                MutationPayloadPlan.create(
                    SubjectPayload.Reflection,
                    AbstractPerson.Reflection,
                    null,
                    null,
                    true,
                    false,
                )
            }
        assertThat(requireNotNull(error.message)).contains("list-valued")
    }

    private fun noWritesClient() =
        DbClient(
            HttpClient(
                MockEngine {
                    error("Unexpected database write")
                },
            ),
            "https://example.test/graphql",
        )

    @Test
    fun `entity field override can select a concrete payload`() {
        val plan =
            MutationPayloadPlan.create(
                AbstractPayload.Reflection,
                AbstractPerson.Reflection,
                explicit = null,
                fieldName = "actor",
                batch = false,
                allowNoEntityField = false,
            )
        val ctx = mockk<MutationFieldExecutionContext<*, *, *, AbstractPayload>>()
        val person = references(ctx)
        assertThat(
            (plan.prepare(ctx, AbstractPerson.Reflection)(records) as ActorPayload).actor,
        ).isSameInstanceAs(person)
    }

    @Test
    fun `unknown entity field fails before any HTTP write`() =
        runBlocking {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    noWritesClient().entity<AbstractPerson>().insert(
                        AbstractMutationContext(mockk()),
                        PgGraphqlObject.of(),
                        payloadType = SubjectPayload.Reflection,
                        entityField = "missing",
                    )
                }
            assertThat(requireNotNull(error.message)).contains("entityField 'missing'")
        }

    @Test
    fun `abstract payload override fails before any HTTP write`() =
        runBlocking {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    noWritesClient().entity<AbstractPerson>().insert(
                        AbstractMutationContext(mockk()),
                        PgGraphqlObject.of(),
                        payloadType = AbstractPayload.Reflection,
                    )
                }
            assertThat(requireNotNull(error.message)).contains("payloadType must identify a concrete object")
        }

    @Test
    fun `singular mutation rejects a list field`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                MutationPayloadPlan.create(
                    BatchSubjectPayload.Reflection,
                    AbstractPerson.Reflection,
                    explicit = null,
                    fieldName = null,
                    batch = false,
                    allowNoEntityField = false,
                )
            }
        assertThat(requireNotNull(error.message)).contains("must be singular")
    }

    private fun <P : CompositeOutput> references(ctx: MutationFieldExecutionContext<*, *, *, P>): AbstractPerson {
        val person = AbstractPerson()
        val id = mockk<GlobalID<AbstractPerson>>()
        every { ctx.globalIDFor(AbstractPerson.Reflection, "person-1") } returns id
        every { ctx.nodeRef(id) } returns person
        return person
    }

    private fun assertk.Assert<SubjectPayload>.subjectEquals(person: AbstractPerson) =
        transform {
            it.subject
        }.isSameInstanceAs(person)

    private val records = Json.parseToJsonElement("""{"records":[{"uuidId":"person-1"}]}""").jsonObject
}
