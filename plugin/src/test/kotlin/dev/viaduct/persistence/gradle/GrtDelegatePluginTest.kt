package dev.viaduct.persistence.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import viaduct.tenant.codegen.cli.SchemaObjectsBytecode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GrtDelegatePluginTest {
    @Test
    fun `delegates opt in compile without editing GRTs and do not alter generated mappings`() {
        val directory = Files.createTempDirectory("grt-delegate-consumer-").toFile()
        try {
            directory.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
                dependencyResolutionManagement { repositories { mavenCentral() } }
                rootProject.name = "grt-delegate-consumer"
                """.trimIndent(),
            )
            generateGrts(directory)
            val grts = grtBytes(directory)
            directory.resolve("build.gradle.kts").writeText(script(directory, enabled = false))
            runGradle(directory, "generateViaductPgPersistenceModel")
            val mapping =
                directory
                    .resolve("build/generated/viaduct-persistence/resources/META-INF")
                    .resolve("viaduct-persistence.hbm.xml")
            val before = mapping.readText()
            directory.resolve("build.gradle.kts").writeText(script(directory, enabled = true))
            val enabled = runGradle(directory, "compileKotlin")
            val compiled =
                directory
                    .resolve("build/classes/kotlin/main/example/grts/persistence")
                    .resolve("PersonEntity.class")
                    .isFile
            val unchanged = before == mapping.readText() && grts == grtBytes(directory)
            directory.resolve("build.gradle.kts").writeText(script(directory, enabled = false))
            runGradle(directory, "compileKotlin")
            val stale =
                directory
                    .resolve("build/generated/viaduct-grt-delegates/kotlin")
                    .walkTopDown()
                    .any { it.extension == "kt" }
            assertEquals(
                listOf(TaskOutcome.SUCCESS, true, true, false),
                listOf(enabled.task(":generateViaductHibernateDelegates")?.outcome, compiled, unchanged, stale),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun generateGrts(directory: File) {
        val schema =
            directory.resolve("schema/Model.graphqls").apply {
                check(parentFile.mkdirs())
                writeText(
                    """
                    directive @resolver(isSelective: Boolean = false) on OBJECT | FIELD_DEFINITION
                    directive @idOf(type: String!) on FIELD_DEFINITION
                    interface Node { id: ID! }
                    type Person implements Node @resolver(isSelective: true) {
                        id: ID!, username: String!, manager: Person, managerId: ID! @idOf(type: "Person")
                    }
                    """.trimIndent(),
                )
            }
        SchemaObjectsBytecode().main(
            listOf(
                "--schema_files",
                schema.path,
                "--generated_directory",
                directory.resolve("grts").path,
                "--pkg_for_generated_classes",
                "example.grts",
                "--include_ineligible_for_testing_only",
            ),
        )
    }

    private fun grtBytes(directory: File) =
        directory
            .resolve("grts")
            .walkTopDown()
            .filter(File::isFile)
            .associate { it.relativeTo(directory).path to it.readBytes().toList() }

    private fun script(
        directory: File,
        enabled: Boolean,
    ): String {
        val classpath =
            (System.getProperty("java.class.path").split(File.pathSeparator) + directory.resolve("grts").path)
                .joinToString { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\"" }
        val option = if (enabled) "delegateGrtPackage.set(\"example.grts\")" else ""
        return """
            plugins { kotlin("jvm") version "2.1.0"; id("dev.viaduct.pg-persistence") }
            dependencies { implementation(files($classpath)) }
            viaductPgPersistence {
                centralSchemaDirectory.set(file("schema"))
                $option
            }
            """.trimIndent()
    }

    private fun runGradle(
        directory: File,
        vararg tasks: String,
    ) = GradleRunner
        .create()
        .withProjectDir(directory)
        .withPluginClasspath()
        .withArguments(*tasks, "--no-parallel", "--stacktrace")
        .build()
}
