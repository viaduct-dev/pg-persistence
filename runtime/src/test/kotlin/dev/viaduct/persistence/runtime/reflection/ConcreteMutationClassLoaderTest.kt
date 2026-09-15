@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.reflection

import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbRequestHeaders
import dev.viaduct.persistence.runtime.db.MissingRecordMutationContext
import dev.viaduct.persistence.runtime.db.MutationRecord
import dev.viaduct.persistence.runtime.db.PgGraphqlDelete
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.RecordPayload
import dev.viaduct.persistence.runtime.db.reflectedType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import kotlin.test.Test
import kotlin.test.assertSame

class ConcreteMutationClassLoaderTest {
    @Test
    fun `builders use the concrete GRT classloader`() {
        val loader = loader()
        assertSame(loader, GeneratedTypeReflection().builderClass(reflection(loader)).classLoader)
    }

    @Test
    fun `fields use the concrete GRT classloader`() {
        val loader = loader()
        val field = GeneratedFieldReflection().allFields(reflection(loader)).single()
        assertSame(loader, field.containingType.kcls.java.classLoader)
    }

    @Test
    fun `mutation entity reflection uses the concrete GRT classloader`() {
        val loader = loader()
        val node = loader.loadClass(MutationRecord::class.java.name).asSubclass(NodeObject::class.java)
        assertSame(loader, reflectedType(node).kcls.java.classLoader)
    }

    @Test
    fun `resolver payload uses its own GRT classloader`() =
        runBlocking {
            val loader = loader()

            @Suppress("UNCHECKED_CAST")
            val ctx =
                loader
                    .loadClass(MissingRecordMutationContext::class.java.name)
                    .getConstructor(MutationFieldExecutionContext::class.java)
                    .newInstance(mockk<MutationFieldExecutionContext<*, *, *, *>>()) as
                    MutationFieldExecutionContext<*, *, *, CompositeOutput>
            HttpClient(
                MockEngine {
                    respond(
                        """{"data":{"deleteFromMutationRecordCollection":{"affectedCount":1,"records":[]}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ).use { http ->
                val client = DbClient(http, "https://example.test/graphql/v1", DbRequestHeaders { emptyMap() })
                val mutation = PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", "record-1"))
                val payload = client.entity<MutationRecord>().delete(ctx, mutation)
                assertSame(loader, payload.javaClass.classLoader)
            }
        }

    private fun reflection(loader: ClassLoader): Type<*> =
        loader.loadClass(RecordPayload::class.java.name + "\$Reflection").getField("INSTANCE").get(null) as Type<*>

    private fun loader() =
        IsolatedGrtLoader(
            MutationRecord::class.java,
            listOf(
                "MutationRecord",
                "RecordPayload",
                "MissingRecordPayload",
                "MissingRecordMutationContext",
                "MutationFixtureType",
                "MutationFixtureField",
            ),
        )
}
