package dev.viaduct.persistence.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SelectiveNodePluginTest {
    private lateinit var directory: File

    @BeforeEach
    fun setUp(
        @TempDir directory: File,
    ) {
        this.directory = directory
    }

    @Test
    fun `single project compiles and executes generated selective resolvers`() {
        prepareConsumer(":")
        runGradle("test", "generateViaductGRTs")
        assertSchemaContribution(directory)
    }

    @Test
    fun `explicit selective nodes do not contribute an empty graphql document`() {
        prepareConsumer(":")
        runGradle("assembleViaductCentralSchema")
        val source =
            directory
                .resolve("src/main/viaduct/schema")
                .walkTopDown()
                .single { it.isFile && it.readText().contains("type Group implements Node") }
        source.writeText(
            source.readText().replace(
                "type Group implements Node {",
                "type Group implements Node @resolver(isSelective: true) {",
            ),
        )
        runGradle("assembleViaductCentralSchema")
        assertFalse(schemaContribution(directory).exists())
    }

    @Test
    fun `separate module regenerates defaults when policy and source schemas change`() {
        val module = prepareConsumer(":groups")
        runGradle(":groups:test", ":generateViaductGRTs")
        assertSchemaContribution(module)
        val other = directory.resolve("build/viaduct/centralSchema/partition/other/graphql/Other.graphqls")
        assertFalse(other.readText().contains("isSelective"))
        val again = runGradle(":groups:assembleViaductSchemaContributions")
        assertEquals(
            TaskOutcome.UP_TO_DATE,
            again.task(":groups:generateViaductPgPersistenceSchemaContributions")?.outcome,
        )

        val source = module.resolve("src/main/viaduct/schema/External.graphqls")
        source.writeText("type External implements Node @resolver { id: ID! }")
        val generated = schemaContribution(module)
        val failure = runGradleAndFail(":groups:assembleViaductSchemaContributions")
        assertContains(failure.output, "existing @resolver that is not selective")
        source.writeText("type External implements Node { id: ID! }")
        runGradle(":groups:assembleViaductSchemaContributions")
        assertContains(generated.readText(), "extend type External @resolver(isSelective: true)")

        module.resolve("src/main/viaduct/persistence.yaml").writeText("denyList:\n  types: [External]\n")
        runGradle(":groups:assembleViaductSchemaContributions")
        assertFalse(generated.readText().contains("extend type External"))
        check(source.delete())
        runGradle(":groups:assembleViaductSchemaContributions")
        assertFalse(generated.readText().contains("extend type External"))
    }

    private fun prepareConsumer(modulePath: String): File {
        writeSettings(modulePath)
        directory.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g\n")
        val module = if (modulePath == ":") directory else directory.resolve("groups").apply { check(mkdirs()) }
        File(javaClass.getResource("/selective-nodes")!!.toURI()).copyRecursively(module, overwrite = true)
        module.resolve("build.gradle.kts").writeText(moduleBuildScript(modulePath == ":"))
        if (modulePath != ":") {
            val other = directory.resolve("other").apply { check(mkdirs()) }
            other.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    kotlin("jvm")
                    id("com.google.devtools.ksp")
                    id("com.airbnb.viaduct.module-gradle-plugin")
                }
                """.trimIndent(),
            )
            check(other.resolve("src/main/viaduct/schema").mkdirs())
            other
                .resolve("src/main/viaduct/schema/Other.graphqls")
                .writeText("type OtherNode implements Node { id: ID! }")
            directory.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    kotlin("jvm")
                    id("com.airbnb.viaduct.application-gradle-plugin")
                }
                """.trimIndent(),
            )
        }
        return module
    }

    private fun writeSettings(modulePath: String) {
        val otherModule =
            if (modulePath == ":") {
                ""
            } else {
                """
                includeModule {
                    project(":other")
                    modulePackageSuffix("other")
                }
                """.trimIndent()
            }
        directory.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    maven("https://central.sonatype.com/repository/maven-snapshots/")
                    gradlePluginPortal()
                }
            }
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }
            dependencyResolutionManagement {
                repositories {
                    mavenLocal()
                    maven("https://central.sonatype.com/repository/maven-snapshots/")
                    mavenCentral()
                }
            }
            rootProject.name = "selective-node-consumer"
            includeViaductApplication {
                project(":")
                modulePackagePrefix("com.example")
                includeModule {
                    project("$modulePath")
                    modulePackageSuffix("groups")
                }
                $otherModule
            }
            """.trimIndent(),
        )
    }

    private fun moduleBuildScript(isApplication: Boolean): String {
        val applicationPlugin =
            if (isApplication) {
                "id(\"com.airbnb.viaduct.application-gradle-plugin\")"
            } else {
                ""
            }
        return """
            plugins {
                kotlin("jvm")
                id("com.google.devtools.ksp")
                $applicationPlugin
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("dev.viaduct.pg-persistence")
            }
            dependencies {
                implementation(files(
                    providers.gradleProperty("consumerRuntimeClasspath").get().split(File.pathSeparator)
                ))
                testImplementation(kotlin("test"))
                testImplementation(kotlin("reflect"))
                testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                testImplementation("io.ktor:ktor-client-mock:3.2.0")
            }
            tasks.test {
                useJUnitPlatform()
                testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            """.trimIndent()
    }

    private fun runGradle(vararg tasks: String) =
        GradleRunner
            .create()
            .withProjectDir(directory)
            .withPluginClasspath(pluginClasspath())
            .forwardOutput()
            .withArguments(
                *tasks,
                "-PconsumerRuntimeClasspath=${System.getProperty("consumerRuntimeClasspath")}",
                "--stacktrace",
            ).build()

    private fun runGradleAndFail(vararg tasks: String) =
        GradleRunner
            .create()
            .withProjectDir(directory)
            .withPluginClasspath(pluginClasspath())
            .forwardOutput()
            .withArguments(
                *tasks,
                "-PconsumerRuntimeClasspath=${System.getProperty("consumerRuntimeClasspath")}",
                "--stacktrace",
            ).buildAndFail()

    private fun pluginClasspath(): List<File> {
        val metadata =
            Properties().apply {
                SelectiveNodePluginTest::class.java
                    .getResource("/plugin-under-test-metadata.properties")!!
                    .readText()
                    .reader()
                    .use { load(it) }
            }
        return (
            metadata.getProperty("implementation-classpath") + File.pathSeparator +
                System.getProperty("consumerPluginClasspath")
        ).split(File.pathSeparator)
            .map(::File)
            .distinct()
    }

    private fun assertSchemaContribution(module: File) {
        val source = module.resolve("src/main/viaduct/schema/Group.graphqls").readText()
        assertFalse(source.contains("isSelective"))
        val generated = schemaContribution(module).readText()
        assertContains(generated, "extend type Group @resolver(isSelective: true)")
        val packaged = directory.resolve("build/viaduct/centralSchema/partition/groups/graphql/Group.graphqls")
        assertEquals(source, packaged.readText())
        val centralContributions = directory.resolve("build/viaduct/centralSchema/schemabase/contributions")
        assertContains(
            centralContributions.listFiles().orEmpty().joinToString("\n") { it.readText() },
            "extend type Group @resolver(isSelective: true)",
        )
    }

    private fun schemaContribution(module: File): File =
        module.resolve("build/generated/viaduct-persistence-schema-contributions/pg-persistence.graphqls")
}
