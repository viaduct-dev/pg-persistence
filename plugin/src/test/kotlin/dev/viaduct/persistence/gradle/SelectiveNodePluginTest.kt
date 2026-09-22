package dev.viaduct.persistence.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SelectiveNodePluginTest {
    private lateinit var directory: File

    @BeforeEach
    fun setUp(
        @TempDir directory: File,
    ) {
        this.directory = directory
    }

    @Test
    fun `single project compiles and executes explicitly declared selective resolvers`() {
        prepareConsumer(":")
        val result = runGradle("test", "generateViaductGRTs")
        assertNull(result.task(":prepareViaductPgPersistenceSchema"))
        assertNull(result.task(":generateViaductPgPersistenceSchemaContributions"))
        assertUnchangedSchema(directory)
    }

    @Test
    fun `missing selective declaration fails validation without rewriting the schema`() {
        prepareConsumer(":")
        val source = directory.resolve("src/main/viaduct/schema/Group.graphqls")
        val original = source.readText().replace(" @resolver(isSelective: true)", "")
        source.writeText(original)

        val result = runGradleAndFail("validateViaductPgPersistenceSchema")

        assertContains(result.output, "Persistent Node 'Group' requires an explicit @resolver(isSelective: true)")
        assertEquals(original, source.readText())
        assertFalse(directory.resolve("build/generated/viaduct-persistence-schema").exists())
    }

    @Test
    fun `separate module validates source and policy changes without altering other modules`() {
        val module = prepareConsumer(":groups")
        runGradle(":groups:test", ":generateViaductGRTs")
        assertUnchangedSchema(module)
        val other = directory.resolve("build/viaduct/centralSchema/partition/other/graphql/Other.graphqls")
        assertFalse(other.readText().contains("isSelective"))

        val source = module.resolve("src/main/viaduct/schema/External.graphqls")
        source.writeText("type External implements Node @resolver { id: ID! }")
        val failure = runGradleAndFail(":groups:validateViaductPgPersistenceSchema")
        assertContains(failure.output, "Persistent Node 'External' requires an explicit @resolver(isSelective: true)")
        source.writeText("type External implements Node @resolver(isSelective: true) { id: ID! }")
        runGradle(":groups:validateViaductPgPersistenceSchema")

        source.writeText("type External implements Node { id: ID! }")
        module.resolve("src/main/viaduct/persistence.yaml").writeText("denyList:\n  types: [External]\n")
        runGradle(":groups:validateViaductPgPersistenceSchema")
        assertFalse(source.readText().contains("isSelective"))
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

    private fun assertUnchangedSchema(module: File) {
        val source = module.resolve("src/main/viaduct/schema/Group.graphqls").readText()
        val expected = javaClass.getResource("/selective-nodes/src/main/viaduct/schema/Group.graphqls")!!.readText()
        assertEquals(expected, source)
        assertFalse(module.resolve("build/generated/viaduct-persistence-schema").exists())
        assertFalse(module.resolve("build/generated/viaduct-persistence-schema-contributions").exists())
        val packaged = directory.resolve("build/viaduct/centralSchema/partition/groups/graphql/Group.graphqls")
        assertEquals(source, packaged.readText())
    }
}
