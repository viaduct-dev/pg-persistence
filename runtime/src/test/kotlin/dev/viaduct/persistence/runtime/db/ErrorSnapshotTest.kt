package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ErrorSnapshotTest {
    @Test
    fun `result snapshots caller errors`() {
        val error = UpstreamGraphqlError("missing")
        val errors = mutableListOf(error)
        val result = DbResult("value", errors)
        errors.clear()
        assertEquals(listOf(error), result.errors)
    }

    @Test
    fun `result errors cannot be changed through its getter`() {
        val result = DbResult("value", listOf(UpstreamGraphqlError("missing")))
        assertFailsWith<UnsupportedOperationException> { (result.errors as MutableList).clear() }
    }

    @Test
    fun `error snapshots path and locations`() {
        val path = mutableListOf(JsonPrimitive("group"))
        val locations = mutableListOf(UpstreamGraphqlLocation(1, 2))
        val error = UpstreamGraphqlError("missing", path, locations)
        path.clear()
        locations.clear()
        assertEquals(
            UpstreamGraphqlError("missing", listOf(JsonPrimitive("group")), listOf(UpstreamGraphqlLocation(1, 2))),
            error,
        )
    }

    @Test
    fun `error snapshots nested extension values`() {
        val values = mutableListOf(JsonPrimitive("original"))
        val details = mutableMapOf("values" to JsonArray(values))
        val extensions = mutableMapOf("details" to JsonObject(details))
        val error = UpstreamGraphqlError("missing", extensions = JsonObject(extensions))
        values.clear()
        details.clear()
        extensions.clear()
        assertEquals(
            JsonObject(mapOf("details" to JsonObject(mapOf("values" to JsonArray(listOf(JsonPrimitive("original"))))))),
            error.extensions,
        )
    }

    @Test
    fun `nested extension entries cannot be changed through getters`() {
        val error =
            UpstreamGraphqlError(
                "missing",
                extensions = JsonObject(mapOf("details" to JsonObject(mapOf("code" to JsonPrimitive("original"))))),
            )
        val entry =
            error.extensions
                .getValue("details")
                .jsonObject.entries
                .single()
        assertFailsWith<UnsupportedOperationException> {
            (entry as MutableMap.MutableEntry).setValue(JsonPrimitive("changed"))
        }
    }

    @Test
    fun `copy snapshots replacement errors and retains value semantics`() {
        val error = UpstreamGraphqlError("missing")
        val errors = mutableListOf(error)
        val result = DbResult("value").copy(errors = errors)
        errors.clear()
        val (data, copiedErrors) = result
        assertEquals(DbResult(data, copiedErrors), result)
        assertEquals(DbResult("value", listOf(error)).hashCode(), result.hashCode())
    }

    @Test
    fun `error copy snapshots replacement path`() {
        val path = mutableListOf(JsonPrimitive("group"))
        val error = UpstreamGraphqlError("missing").copy(path = path)
        path.clear()
        assertEquals(UpstreamGraphqlError("missing", listOf(JsonPrimitive("group"))), error)
    }
}
