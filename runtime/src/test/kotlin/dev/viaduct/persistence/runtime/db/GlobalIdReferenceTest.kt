@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.node.GlobalIdReferencePlanner
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class GlobalIdReferenceTest {
    private fun reference() =
        GlobalIdReferencePlanner
            .plan(
                mockk<SelectionSet<IdOwner>> {
                    every { contains(IdOwner.Fields.recordId) } returns true
                },
                IdOwner.Reflection,
            ).single()

    @Test
    fun `foreign key selection requests the existing UUID column`() {
        assertEquals("_viaduct_ref_recordId: recordId", reference().upstreamSelection)
    }

    @Test
    fun `foreign key UUID becomes a typed global ID using the resolver context`() {
        val ctx = mockk<ResolverExecutionContext<out Query>>()
        val id = mockk<GlobalID<MutationRecord>>()
        every { ctx.globalIDFor(MutationRecord.Reflection, "record-1") } returns id
        val response = Json.parseToJsonElement("""{"_viaduct_ref_recordId":"record-1"}""").jsonObject
        val result =
            NodeReferenceHydrator(GeneratedTypeReflection()).attach(
                IdOwner(null),
                response,
                listOf(reference()),
                ctx,
            )
        assertSame(id, result.recordId)
    }

    @Test
    fun `null foreign key remains null`() {
        val response = Json.parseToJsonElement("""{"_viaduct_ref_recordId":null}""").jsonObject
        val result =
            NodeReferenceHydrator(GeneratedTypeReflection()).attach(
                IdOwner(null),
                response,
                listOf(reference()),
                mockk(),
            )
        assertNull(result.recordId)
    }
}

class IdOwner(
    val recordId: GlobalID<MutationRecord>?,
) : NodeObject {
    object Reflection : Type<IdOwner> by MutationFixtureType(IdOwner::class)

    object Fields {
        val recordId =
            object : Field<IdOwner> {
                override val name = "recordId"
                override val containingType = Reflection
            }
    }

    fun toBuilder() = Builder().recordId(recordId)

    class Builder {
        private var recordId: GlobalID<MutationRecord>? = null

        fun recordId(value: GlobalID<MutationRecord>?) = apply { recordId = value }

        fun build() = IdOwner(recordId)
    }
}
