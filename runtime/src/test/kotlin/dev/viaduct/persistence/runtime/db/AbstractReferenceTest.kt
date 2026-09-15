@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AbstractReferenceTest {
    @Test
    fun `association writes can share a transaction with ordinary entity operations`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()
            val association = AbstractActivity.Fields.subjects.pgGraphqlAssociation()
            fixture.execute {
                insert(
                    association.entity,
                    association.insertObject(
                        id(AbstractActivity.Reflection, "activity"),
                        id(AbstractGroup.Reflection, "group"),
                    ),
                )
                update(
                    association.entity,
                    PgGraphqlUpdate(
                        association.withTarget(PgGraphqlObject.of(), id(AbstractPerson.Reflection, "person")),
                        PgGraphqlFilter.eq("uuidId", "row"),
                    ),
                )
                delete(association.entity, PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", "old-row")))
            }
            val variables =
                fixture
                    .requests()
                    .single()
                    .getValue("variables")
                    .jsonObject
            assertThat(variables.getValue("operation0Objects").jsonArray.single()).isEqualTo(
                Json.parseToJsonElement(
                    """{"ownerId":"activity","nodeAbstractPersonId":null,"nodeAbstractGroupId":"group"}""",
                ),
            )
        }

    @Test
    fun `switching concrete target clears the old foreign key`() {
        val previous = PgGraphqlObject.of("subjectAbstractGroupId" to "old-group", "name" to "activity")
        val result =
            previous.withReference(
                AbstractActivity.Fields.subject,
                id(AbstractPerson.Reflection, "new-person"),
            )
        assertThat(result.encoded()).isEqualTo(
            Json.parseToJsonElement(
                """
                {"subjectAbstractPersonId":"new-person","subjectAbstractGroupId":null,"name":"activity"}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `null reference clears all possible foreign keys`() {
        assertThat(PgGraphqlObject.of().withReference(AbstractActivity.Fields.subject, null).encoded()).isEqualTo(
            Json.parseToJsonElement("""{"subjectAbstractPersonId":null,"subjectAbstractGroupId":null}"""),
        )
    }

    @Test
    fun `required reference cannot be cleared`() {
        val relationship = AbstractRelationship("Activity", "subject", "Subject", setOf("AbstractPerson"), false)
        assertFailsWith<IllegalArgumentException> { PgGraphqlObject.of().withReference(relationship, null) }
    }

    @Test
    fun `unrelated type is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PgGraphqlObject.of().withReference(
                AbstractActivity.Fields.subject,
                id(AbstractActivity.Reflection, "owner"),
            )
        }
    }

    @Test
    fun `mixed association rows contain owner and exactly one target`() {
        val association = AbstractActivity.Fields.subjects.pgGraphqlAssociation()
        assertThat(
            association
                .insertObject(
                    id(AbstractActivity.Reflection, "owner"),
                    id(AbstractGroup.Reflection, "group"),
                ).encoded(),
        ).isEqualTo(
            Json.parseToJsonElement(
                """{"ownerId":"owner","nodeAbstractPersonId":null,"nodeAbstractGroupId":"group"}""",
            ),
        )
    }

    @Test
    fun `wrong association owner is rejected`() {
        val association = AbstractActivity.Fields.subjects.pgGraphqlAssociation()
        assertFailsWith<IllegalArgumentException> {
            association.insertObject(id(AbstractPerson.Reflection, "person"), id(AbstractGroup.Reflection, "group"))
        }
    }

    @Test
    fun `abstract response becomes a concrete node reference`() {
        val context = mockk<ResolverExecutionContext<Query>>()
        val globalId = id(AbstractPerson.Reflection, "person")
        val person = AbstractPerson()
        every { context.globalIDFor(AbstractPerson.Reflection, "person") } returns globalId
        every { context.nodeRef(globalId) } returns person
        val result =
            NodeReferenceResolver().resolve(
                context,
                AbstractSubject.Reflection,
                Json.parseToJsonElement("""{"__typename":"AbstractPerson","uuidId":"person"}""").jsonObject,
            )
        assertThat(result).isSameInstanceAs(person)
    }

    @Test
    fun `abstract node without concrete typename is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NodeReferenceResolver().resolve(
                mockk(),
                AbstractSubject.Reflection,
                Json.parseToJsonElement("""{"uuidId":"person"}""").jsonObject,
            )
        }
    }

    @Test
    fun `concrete node reference does not require typename`() {
        val context = mockk<ResolverExecutionContext<Query>>()
        val globalId = id(AbstractPerson.Reflection, "person")
        val person = AbstractPerson()
        every { context.globalIDFor(AbstractPerson.Reflection, "person") } returns globalId
        every { context.nodeRef(globalId) } returns person
        val result =
            NodeReferenceResolver().resolve(
                context,
                AbstractPerson.Reflection,
                Json.parseToJsonElement("""{"uuidId":"person"}""").jsonObject,
            )
        assertThat(result).isSameInstanceAs(person)
    }

    @Test
    fun `abstract node with unknown concrete typename is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NodeReferenceResolver().resolve(
                mockk(),
                AbstractSubject.Reflection,
                Json.parseToJsonElement("""{"__typename":"AbstractActivity","uuidId":"owner"}""").jsonObject,
            )
        }
    }

    private fun <T : NodeObject> id(
        type: Type<T>,
        value: String,
    ): GlobalID<T> =
        mockk<GlobalID<T>>().also {
            every { it.type } returns type
            every { it.internalID } returns value
        }
}
