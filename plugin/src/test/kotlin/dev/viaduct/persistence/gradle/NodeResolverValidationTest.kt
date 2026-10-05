package dev.viaduct.persistence.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class NodeResolverValidationTest {
    private lateinit var directory: File

    @BeforeEach
    fun setUp(
        @TempDir directory: File,
    ) {
        this.directory = directory
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "@resolver",
            "@resolver(isSelective: false)",
            "@resolver(isBatching: true)",
        ],
    )
    fun `persistent nodes require an explicit selective resolver`(directive: String) {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                validate("type Group implements Node $directive { id: ID!, name: String }")
            }

        assertContains(failure.message.orEmpty(), "Persistent Node 'Group' requires an explicit")
        assertContains(failure.message.orEmpty(), "@resolver(isSelective: true)")
        assertContains(failure.message.orEmpty(), "types.Group.excluded")
    }

    @Test
    fun `selective persistent nodes pass validation`() {
        validate(
            """
            type Group implements Node @resolver(isSelective: true) { id: ID!, owner: Person }
            type Person implements Node @resolver(isSelective: true) { id: ID!, name: String }
            """.trimIndent(),
        )
    }

    @Test
    fun `selective resolver declarations on extensions pass validation`() {
        validate(
            """
            type Group implements Node { id: ID!, name: String }
            extend type Group @resolver(isSelective: true)
            """.trimIndent(),
        )
    }

    @Test
    fun `modern connection arguments require a field resolver`() {
        assertFailsWith<IllegalArgumentException> {
            validate(modernConnectionSchema(""))
        }
    }

    @Test
    fun `modern connection field resolvers pass validation`() {
        validate(modernConnectionSchema("@resolver"))
    }

    @Test
    fun `nested modern connections also require a field resolver`() {
        assertFailsWith<IllegalArgumentException> {
            validate(
                modernConnectionSchema("")
                    .replace("members(first: Int, after: String): MemberConnection!", "directory: Directory") +
                    "\ntype Directory { members(first: Int): MemberConnection! }",
            )
        }
    }

    private fun modernConnectionSchema(resolver: String) =
        """
        type Group implements Node @resolver(isSelective: true) {
          id: ID!
          members(first: Int, after: String): MemberConnection! $resolver
        }
        type Person implements Node @resolver(isSelective: true) { id: ID!, name: String }
        type MemberConnection @connection { edges: [MemberEdge!]!, pageInfo: PageInfo! }
        type MemberEdge @edge { node: Person!, cursor: String! }
        """.trimIndent()

    @Test
    fun `denied nodes do not require a selective resolver`() {
        val schemaDirectory = directory.resolve("schema").apply { check(mkdirs()) }
        schemaDirectory.resolve("Group.graphqls").writeText("type Group implements Node { id: ID! }")
        val policy =
            directory.resolve("pg-persistence.yaml").apply {
                writeText("types:\n  Group:\n    excluded: true\n")
            }
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val task = project.tasks.register("validate", ValidatePgGraphqlDbsTask::class.java).get()
        task.centralSchemaDirectory.set(schemaDirectory)
        task.persistenceConfigFile.from(policy)

        task.validate()
    }

    private fun validate(schema: String) {
        val schemaDirectory = directory.resolve("schema").apply { check(mkdirs()) }
        schemaDirectory.resolve("Group.graphqls").writeText(schema)
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val task = project.tasks.register("validate", ValidatePgGraphqlDbsTask::class.java).get()
        task.centralSchemaDirectory.set(schemaDirectory)

        task.validate()
    }
}
