package dev.viaduct.persistence.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test

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
            "@resolver(isSelective: true)",
            "@resolver(isBatching: true)",
        ],
    )
    fun `persistence validation does not constrain node resolver declarations`(directive: String) {
        validate("type Group implements Node $directive { id: ID!, name: String }")
    }

    @Test
    fun `nested nodes do not impose a selective resolver requirement`() {
        validate(
            """
            type Group implements Node @resolver { id: ID!, owner: Person }
            type Person implements Node @resolver { id: ID!, name: String }
            """.trimIndent(),
        )
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
