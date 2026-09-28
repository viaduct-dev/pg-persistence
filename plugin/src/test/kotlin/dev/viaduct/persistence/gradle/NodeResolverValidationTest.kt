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
        assertContains(failure.message.orEmpty(), "denyList.types")
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
    fun `denied nodes do not require a selective resolver`() {
        val schemaDirectory = directory.resolve("schema").apply { check(mkdirs()) }
        schemaDirectory.resolve("Group.graphqls").writeText("type Group implements Node { id: ID! }")
        val policy = directory.resolve("persistence.yaml").apply { writeText("denyList:\n  types: [Group]\n") }
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
