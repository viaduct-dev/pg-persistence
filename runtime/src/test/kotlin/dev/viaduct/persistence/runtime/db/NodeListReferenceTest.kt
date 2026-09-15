@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferenceKind
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeListReferenceTest {
    private val reflection = GeneratedTypeReflection()

    private fun reference() =
        NodeReferencePlanner(reflection)
            .plan(
                mockk<SelectionSet<ListOwner>> {
                    every { type } returns ListOwner.Reflection
                    every { contains(ListOwner.Fields.records) } returns true
                    every { toFragment() } returns
                        OutputSelectionFragment(
                            "Main",
                            "fragment Main on ListOwner { records { id } }",
                            emptyMap(),
                        )
                },
                mockk { every { type } returns ListOwner.Reflection },
            ).single()

    @Test
    fun `lists request a connection rather than a to-one foreign key`() {
        val reference = reference()
        assertEquals(NodeReferenceKind.LIST, reference.kind)
        assertEquals(
            "records { edges { node { uuidId } } pageInfo { hasNextPage endCursor } }",
            reference.upstreamSelection,
        )
    }

    @Test
    fun `list references preserve record order`() {
        val ctx = mockk<ResolverExecutionContext<out Query>>()
        val first = MutationRecord()
        val second = MutationRecord()
        listOf("first" to first, "second" to second).forEach { (id, record) ->
            val globalId = mockk<GlobalID<MutationRecord>>()
            every { ctx.globalIDFor(MutationRecord.Reflection, id) } returns globalId
            every { ctx.ref(globalId) } returns record
        }
        val response =
            Json
                .parseToJsonElement(
                    """{"records":{"edges":[{"node":{"uuidId":"first"}},{"node":{"uuidId":"second"}}]}}""",
                ).jsonObject
        val result =
            NodeReferenceHydrator(reflection).attach(
                ListOwner(emptyList()),
                response,
                listOf(reference()),
                ctx,
            )
        assertEquals(listOf(first, second), result.records)
    }

    @Test
    fun `an empty connection becomes an empty list`() {
        val response = Json.parseToJsonElement("""{"records":{"edges":[]}}""").jsonObject
        val result =
            NodeReferenceHydrator(reflection).attach(
                ListOwner(emptyList()),
                response,
                listOf(reference()),
                mockk(),
            )
        assertEquals(emptyList(), result.records)
    }
}

class ListOwner(
    records: List<MutationRecord>,
) : NodeObject {
    val records: List<MutationRecord> = java.util.List.copyOf(records)

    object Reflection : Type<ListOwner> by MutationFixtureType(ListOwner::class)

    object Fields {
        val records = MutationFixtureField("records", Reflection, MutationRecord.Reflection)
    }

    fun toBuilder() = Builder().records(records)

    class Builder {
        private var records: List<MutationRecord> = emptyList()

        fun records(value: List<MutationRecord>) = apply { records = java.util.List.copyOf(value) }

        fun build() = ListOwner(records)
    }
}
