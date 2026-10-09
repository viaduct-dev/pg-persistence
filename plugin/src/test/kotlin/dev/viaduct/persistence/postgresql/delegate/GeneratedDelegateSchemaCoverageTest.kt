@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import viaduct.api.internal.ObjectBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedDelegateSchemaCoverageTest {
    @Test
    fun `structured JSON values retain nested arrays numbers booleans and nulls`() =
        withFixture { f ->
            val value =
                GrtDelegate
                    .toBuilder(scalar(f))
                    .put(
                        "metadata",
                        mapOf("settings" to mapOf("limit" to 3, "enabled" to true), "values" to listOf(1, 1.25, null)),
                    ).build() as ObjectBase
            val saved = f.insert(value)
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(saved), "metadata"))
            assertEquals(values(value, listOf("metadata")), values(loaded, listOf("metadata")))
        }

    @Test
    fun `native arrays JSON UUID scalars and isX booleans round trip through public GRT accessors`() =
        withFixture { f ->
            val original = scalar(f)
            val expected = values(original, FIELDS)
            val saved = f.insert(original)
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(saved), FIELDS.joinToString(" ")))
            assertEquals(expected, values(loaded, FIELDS))
        }

    @Test
    fun `array and JSON replacement uses dirty checking and preserves old values`() =
        withFixture { f ->
            val saved = f.insert(scalar(f))
            val old =
                f.client.transaction(f.context) { session ->
                    val current = f.access.find(f.client, f.context, session, f.id(saved))
                    val replacement =
                        GrtDelegate
                            .toBuilder(current)
                            .put("labels", emptyList<String>())
                            .put("notes", null)
                            .put("metadata", mapOf("changed" to true))
                            .put("isActive", false)
                            .build() as ObjectBase
                    f.access.update(f.client, f.context, session, replacement)
                    current
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(saved), "labels notes metadata isActive"))
            val fields = listOf("labels", "notes", "metadata", "isActive")
            assertEquals(
                listOf(
                    listOf(listOf("a", "b"), listOf("first", null), mapOf("enabled" to true), true),
                    listOf(emptyList<String>(), null, mapOf("changed" to true), false),
                ),
                listOf(values(old, fields), values(loaded, fields)),
            )
        }

    @Test
    fun `omitted nullable arrays and JSON cannot erase stored values`() =
        withFixture { f ->
            val saved = f.insert(scalar(f))
            val rejected =
                f.client.transaction(f.context) { session ->
                    val incomplete =
                        f
                            .builder("Scalar")
                            .put("id", f.id(saved))
                            .put("labels", emptyList<String>())
                            .put("numbers", emptyList<Int>())
                            .put("isActive", false)
                            .build() as ObjectBase
                    runCatching { f.access.update(f.client, f.context, session, incomplete) }.isFailure
                }
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(saved), "notes metadata"))
            assertEquals(
                listOf(true, listOf("first", null), mapOf("enabled" to true)),
                listOf(rejected) + values(loaded, listOf("notes", "metadata")),
            )
        }

    @Test
    fun `unchanged array and JSON fields produce no update`() =
        withFixture { f ->
            val saved = f.insert(scalar(f))
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            f.client.transaction(f.context) { session ->
                f.access.find(f.client, f.context, session, f.id(saved))
                session.flush()
            }
            assertEquals(0L, f.factory.statistics.entityUpdateCount)
        }

    companion object {
        private val FIELDS =
            listOf(
                "labels",
                "notes",
                "numbers",
                "flags",
                "statuses",
                "happenedTimes",
                "rawId",
                "rawIds",
                "metadata",
                "isActive",
                "amount",
                "amounts",
                "dates",
                "longs",
                "ratios",
                "happenedAt",
                "isLabel",
            )

        private fun scalar(f: GeneratedDelegateFixture): ObjectBase {
            val id = UUID.randomUUID().toString()
            val status =
                f.loader
                    .loadClass("dev.viaduct.persistence.approvalfixture.DelegateStatus${f.suffix}")
                    .enumConstants
            return f
                .builder("Scalar")
                .put("labels", listOf("a", "b"))
                .put("notes", listOf("first", null))
                .put("numbers", listOf(1, 2))
                .put("flags", listOf(true, null, false))
                .put("statuses", listOf(status.first(), null, status.last()))
                .put("happenedTimes", listOf(Instant.parse("2026-10-09T10:00:00Z"), null))
                .put("rawId", id)
                .put("rawIds", listOf(id))
                .put("metadata", mapOf("enabled" to true))
                .put("isActive", true)
                .put("amount", BigDecimal("12.50"))
                .put("amounts", listOf(BigDecimal("12.50"), null))
                .put("dates", listOf(LocalDate.of(2026, 10, 9)))
                .put("longs", listOf(10000000000L))
                .put("ratios", listOf(1.25))
                .put("happenedAt", Instant.parse("2026-10-09T10:00:00Z"))
                .put("isLabel", "label")
                .build() as ObjectBase
        }

        private fun values(
            value: ObjectBase,
            fields: List<String>,
        ): List<Any?> =
            fields.map { name ->
                val method = if (name.startsWith("is")) name else "get${name.replaceFirstChar(Char::uppercaseChar)}"
                value.javaClass.getMethod(method).invoke(value)
            }
    }
}
