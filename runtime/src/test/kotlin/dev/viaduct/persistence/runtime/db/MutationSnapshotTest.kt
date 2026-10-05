package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MutationSnapshotTest {
    @Test
    fun `prepared transaction request stays unchanged after nested entry mutation`() {
        val prepared =
            DbPreparedTransaction.create(
                "operation",
                "scope",
                Json
                    .parseToJsonElement(
                        """
                        {"protocolVersion":1,"operationName":"DbTransaction",
                         "document":"mutation DbTransaction { __typename }","variables":{"values":{"name":"original"}}}
                        """.trimIndent(),
                    ).jsonObject,
                1,
            )
        val expected = prepared.request.toString()
        val entry =
            prepared.request
                .getValue("variables")
                .jsonObject
                .getValue("values")
                .jsonObject.entries
                .single()
        runCatching { (entry as MutableMap.MutableEntry).setValue(JsonPrimitive("changed")) }
        assertThat(prepared.request.toString()).isEqualTo(expected)
    }

    @Test
    fun `prepared mutation snapshots definitions and variables`() {
        val definitions = mutableListOf("first", "second")
        val variables = linkedMapOf<String, JsonElement>("first" to JsonPrimitive(1), "second" to JsonPrimitive(2))
        val mutation = PreparedMutation(definitions, "insert", variables)

        definitions.clear()
        variables.clear()

        assertThat(mutation.definitions).containsExactly("first", "second")
        assertThat(mutation.variables.entries.map { it.key to it.value }).containsExactly(
            "first" to JsonPrimitive(1),
            "second" to JsonPrimitive(2),
        )
    }

    @Test
    fun `prepared mutation collections cannot be modified through casts`() {
        val mutation = PreparedMutation(listOf("first"), "insert", mapOf("first" to JsonPrimitive(1)))

        assertFailsWith<UnsupportedOperationException> { (mutation.definitions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (mutation.variables as MutableMap).clear() }
    }

    @Test
    fun `exception retains the errors described by its message`() {
        val error = UpstreamGraphqlError("Could not read group name")
        val errors = mutableListOf(error)
        val exception = UpstreamGraphqlException(errors)

        errors.clear()

        assertThat(exception.errors).containsExactly(error)
        assertThat(exception.message).isEqualTo("Db fetch failed: Could not read group name")
    }

    @Test
    fun `exception errors cannot be removed through a cast`() {
        val exception = UpstreamGraphqlException(listOf(UpstreamGraphqlError("failed")))

        assertFailsWith<UnsupportedOperationException> { (exception.errors as MutableList).clear() }
    }
}
