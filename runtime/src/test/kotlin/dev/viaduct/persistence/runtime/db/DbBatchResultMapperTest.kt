package dev.viaduct.persistence.runtime.db

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.errors.ErroneousFieldException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DbBatchResultMapperTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `hydration does not turn cancellation or fatal failures into node errors`(cancelled: Boolean) {
        val failure = if (cancelled) CancellationException("Cancelled hydration") else AssertionError("Fatal hydration")
        val actual =
            runCatching {
                DbBatchResultMapper.map(
                    requestedIds = listOf("group-1"),
                    collectionField = "groupCollection",
                    responseKey = "groupCollection",
                    data = Json.parseToJsonElement(response("group-1")).jsonObject,
                    errors = emptyList(),
                ) { throw failure }
            }.exceptionOrNull()

        assertSame(failure, actual)
    }

    @Test
    fun `returns successful rows and an error value for a missing row`() {
        val result = map(ids = listOf("group-1", "group-2"), response = response("group-1"))

        assertEquals("group-1", result.getValue("group-1").get())
        val error = assertFailsWith<ErroneousFieldException> { result.getValue("group-2").get() }
        assertEquals("MISSING_ROW", error.fieldErrors.single().extensions["code"])
        assertEquals("group-2", error.fieldErrors.single().extensions["internalId"])
    }

    @Test
    fun `associates an upstream edge error with only that node`() {
        val error =
            UpstreamGraphqlError(
                message = "Could not read group name",
                path =
                    listOf(
                        JsonPrimitive("groupCollection"),
                        JsonPrimitive("edges"),
                        JsonPrimitive(1),
                        JsonPrimitive("node"),
                        JsonPrimitive("name"),
                    ),
                extensions = buildJsonObject { put("code", "DATABASE_FIELD_ERROR") },
            )

        val result =
            map(
                ids = listOf("group-1", "group-2"),
                response = response("group-1", "group-2"),
                errors = listOf(error),
            )

        assertEquals("group-1", result.getValue("group-1").get())
        val failure = assertFailsWith<ErroneousFieldException> { result.getValue("group-2").get() }
        assertEquals("Could not read group name", failure.fieldErrors.single().message)
        assertEquals("DATABASE_FIELD_ERROR", failure.fieldErrors.single().extensions["code"])
    }

    @Test
    fun `does not copy a provider path into the Viaduct field error`() {
        val result =
            map(
                ids = listOf("group-1"),
                response = response("group-1"),
                errors =
                    listOf(
                        UpstreamGraphqlError(
                            message = "failed",
                            path =
                                listOf(
                                    JsonPrimitive("edges"),
                                    JsonPrimitive(0),
                                    JsonPrimitive("node"),
                                    JsonPrimitive("name"),
                                ),
                        ),
                    ),
            )

        val failure = assertFailsWith<ErroneousFieldException> { result.getValue("group-1").get() }
        assertEquals(null, failure.fieldErrors.single().path)
    }

    @Test
    fun `throws an unassociated upstream error instead of discarding it`() {
        val error = UpstreamGraphqlError(message = "collection failed")

        val failure =
            assertFailsWith<ErroneousFieldException> {
                map(ids = listOf("group-1"), response = response("group-1"), errors = listOf(error))
            }

        assertEquals("collection failed", failure.fieldErrors.single().message)
    }

    @Test
    fun `returns one entry for duplicate requested UUIDs`() {
        val result = map(ids = listOf("group-1", "group-1"), response = response("group-1"))

        assertEquals(setOf("group-1"), result.keys)
    }

    @Test
    fun `null errored nodes do not discard identifiable successful nodes`() {
        val result =
            map(
                ids = listOf("bad", "good"),
                response = """{"edges":[{"node":{"uuidId":"good"}},{"node":null}]}""",
                errors =
                    listOf(
                        UpstreamGraphqlError(
                            "bad node",
                            listOf(JsonPrimitive("edges"), JsonPrimitive(1), JsonPrimitive("node")),
                        ),
                    ),
            )
        val failure = runCatching { result.getValue("bad").get() }.exceptionOrNull() as? ErroneousFieldException
        assertEquals("good" to "bad node", result.getValue("good").get() to failure?.fieldErrors?.single()?.message)
    }

    private fun map(
        ids: List<String>,
        response: String,
        errors: List<UpstreamGraphqlError> = emptyList(),
    ) = DbBatchResultMapper.map(
        requestedIds = ids,
        collectionField = "groupCollection",
        responseKey = "groupCollection",
        data = Json.parseToJsonElement(response).jsonObject,
        errors = errors,
    ) { node -> node.getValue("uuidId").toString().trim('"') }

    private fun response(vararg ids: String): String =
        ids.joinToString(
            prefix = "{\"edges\":[",
            postfix = "]}",
        ) { id -> "{\"node\":{\"uuidId\":\"$id\"}}" }
}
