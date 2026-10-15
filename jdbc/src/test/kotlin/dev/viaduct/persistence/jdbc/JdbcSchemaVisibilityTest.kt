package dev.viaduct.persistence.jdbc

import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdbcSchemaVisibilityTest {
    @Test
    fun `schema identifiers are copied quoted and bound after trusted role setup`() {
        val statement = mockk<PreparedStatement>(relaxed = true)
        val connection = mockk<Connection>()
        val sql = slot<String>()
        every { connection.autoCommit } returns false
        every { connection.prepareStatement(capture(sql)) } returns statement
        val roleSetup = mockk<JdbcRequestSetup>(relaxed = true)
        val schemas = mutableListOf("x\"; RESET ROLE;--", "public", "public")
        val visibility = JdbcSchemaVisibility(schemas, roleSetup)
        schemas.clear()

        visibility.configure(connection, mapOf("authorization" to "trusted-test-value"))

        verifyOrder {
            connection.autoCommit
            roleSetup.configure(connection, mapOf("authorization" to "trusted-test-value"))
            connection.autoCommit
            statement.setString(1, "\"public\",\"x\"\"; RESET ROLE;--\"")
            statement.execute()
            statement.close()
        }
        assertEquals("SELECT set_config('search_path', ?, true)", sql.captured)
    }

    @Test
    fun `implicit search path tokens and truncated identifiers are rejected`() {
        listOf("", " ", "\$user", "pg_temp", "a".repeat(64), "é".repeat(32), "a\u0000b").forEach { name ->
            assertFailsWith<IllegalArgumentException> { JdbcSchemaVisibility(listOf(name)) }
        }
        assertFailsWith<IllegalArgumentException> { JdbcSchemaVisibility(emptyList()) }
    }

    @Test
    fun `default setup does not interpret headers as authorization`() {
        val connection = mockk<Connection>()
        every { connection.autoCommit } returns false

        assertFailsWith<IllegalArgumentException> {
            JdbcSchemaVisibility(listOf("public")).configure(connection, mapOf("schemas" to "private"))
        }

        verify(exactly = 1) { connection.autoCommit }
        confirmVerified(connection)
    }

    @Test
    fun `autocommit cannot leak schema setup onto the session`() {
        val connection = mockk<Connection>()
        every { connection.autoCommit } returns true
        val roleSetup = mockk<JdbcRequestSetup>(relaxed = true)

        assertFailsWith<IllegalStateException> {
            JdbcSchemaVisibility(listOf("public"), roleSetup).configure(connection, emptyMap())
        }

        verify { connection.autoCommit }
        verify { roleSetup wasNot Called }
        confirmVerified(connection)
    }
}
