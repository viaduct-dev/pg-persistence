package dev.viaduct.persistence.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SelectiveNodePluginTest {
    @Test
    fun `central generation aggregates module placement without compiling resolvers`() {
        val groups = prepareConsumer(":groups")
        groups
            .resolve(
                "src/main/viaduct/pg-persistence.yaml",
            ).writeText("databaseSchema: groups\ntypes:\n  Group:\n    indexes: [[name]]\n")
        val application = directory.resolve("build.gradle.kts")
        application.writeText(
            application.readText().replace(
                "id(\"com.airbnb.viaduct.application-gradle-plugin\")",
                "id(\"com.airbnb.viaduct.application-gradle-plugin\")\nid(\"dev.viaduct.pg-persistence\")",
            ),
        )
        val otherBuild = directory.resolve("other/build.gradle.kts")
        otherBuild.writeText(
            otherBuild.readText().replace(
                "id(\"com.airbnb.viaduct.module-gradle-plugin\")",
                "id(\"com.airbnb.viaduct.module-gradle-plugin\")\nid(\"dev.viaduct.pg-persistence\")",
            ),
        )
        directory.resolve("other/src/main/viaduct/pg-persistence.yaml").writeText("databaseSchema: orders\n")
        directory.resolve("other/src/main/viaduct/schema/Other.graphqls").writeText(
            "type Purchase implements Node @resolver(isSelective: true) { id: ID!, buyer: Group! }",
        )
        val result = runGradle(":generateViaductPgPersistenceModel")
        assertNull(result.task(":groups:compileKotlin"))
        assertNull(result.task(":other:compileKotlin"))
        val resources = directory.resolve("build/generated/viaduct-persistence/resources/META-INF")
        val mapping = resources.resolve("viaduct-persistence.hbm.xml")
        assertContains(mapping.readText(), "schema=\"groups\"")
        assertContains(mapping.readText(), "schema=\"orders\"")
        val second = runGradle(":generateViaductPgPersistenceModel")
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateViaductPgPersistenceModel")?.outcome)
        directory.resolve("other/src/main/viaduct/pg-persistence.yaml").writeText("databaseSchema: purchases\n")
        runGradle(":generateViaductPgPersistenceModel")
        assertContains(mapping.readText(), "schema=\"purchases\"")
        assertContains(mapping.readText(), "schema=\"groups\"")
    }

    @Test
    fun `two module application executes cross-schema DbClient mutations and public queries`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("PG_KAN37_GENERATED_URL") != null,
            "Set PG_KAN37_GENERATED_URL to run the full two-module PostgreSQL consumer",
        )
        val groups = prepareConsumer(":groups")
        val applicationBuild = directory.resolve("build.gradle.kts")
        applicationBuild.writeText(
            applicationBuild.readText().replace(
                "id(\"com.airbnb.viaduct.application-gradle-plugin\")",
                "id(\"com.airbnb.viaduct.application-gradle-plugin\")\nid(\"dev.viaduct.pg-persistence\")",
            ),
        )
        groups.resolve("src/main/kotlin").deleteRecursively()
        groups.resolve("src/main/viaduct/schema").deleteRecursively()
        groups.resolve("src/test").deleteRecursively()
        File(javaClass.getResource("/tenant-schemas")!!.toURI()).copyRecursively(directory, overwrite = true)
        val suffix =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
        val people = "kan37_people_$suffix"
        val orders = "kan37_orders_$suffix"
        groups.resolve("src/main/viaduct/pg-persistence.yaml").writeText("databaseSchema: $people\n")
        directory.resolve("other/src/main/viaduct/pg-persistence.yaml").writeText("databaseSchema: $orders\n")
        val otherBuild = directory.resolve("other/build.gradle.kts")
        otherBuild.appendText(
            """

            apply(plugin = "dev.viaduct.pg-persistence")
            dependencies {
                implementation(files(providers.gradleProperty("consumerRuntimeClasspath").get().split(File.pathSeparator)))
            }
            """.trimIndent(),
        )
        configureTenantSchemaApplication(people, orders)
        val result = runGradle(":test")
        assertNull(result.task(":groups:generateViaductHibernateDelegates"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":other:compileKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
    }

    private fun configureTenantSchemaApplication(
        people: String,
        orders: String,
    ) {
        directory.resolve("build.gradle.kts").appendText(
            """

            dependencies {
                testImplementation(project(":groups"))
                testImplementation(kotlin("reflect"))
                testRuntimeOnly(project(":other"))
                testImplementation(files(providers.gradleProperty("consumerRuntimeClasspath").get().split(File.pathSeparator)))
                testImplementation(kotlin("test"))
                testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
            }
            tasks.test {
                useJUnitPlatform()
                systemProperty("tenantSchemas", "$people,$orders")
                testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            """.trimIndent(),
        )
    }

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
        val result = runGradle("test", "transactionTest", "generateViaductGRTs")
        assertNull(result.task(":prepareViaductPgPersistenceSchema"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateViaductPgPersistenceSchemaContributions")?.outcome)
        assertFalse(contribution(directory).exists())
        assertUnchangedSchema(directory)
    }

    @Test
    fun `node implementation compiles and executes selectively without schema annotations`() {
        prepareConsumer(":")
        val source = directory.resolve("src/main/viaduct/schema/Group.graphqls")
        val original = source.readText().replace(" @resolver(isSelective: true, isBatching: true)", "")
        source.writeText(original)

        runGradle("test", "transactionTest", "generateViaductGRTs")
        assertEquals(original, source.readText())
        assertFalse(directory.resolve("build/generated/viaduct-persistence-schema").exists())
        assertContains(
            contribution(directory).readText(),
            "extend type Group @resolver(isSelective: true, isBatching: true)",
        )
        val second = runGradle("generateViaductPgPersistenceSchemaContributions")
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateViaductPgPersistenceSchemaContributions")?.outcome)

        val resolver = directory.resolve("src/main/kotlin/com/example/groups/GroupResolvers.kt")
        resolver.writeText(resolver.readText().replace("@Resolver\nclass GroupNodeResolver", "class GroupNodeResolver"))
        val third = runGradle("generateViaductPgPersistenceSchemaContributions")
        assertEquals(TaskOutcome.SUCCESS, third.task(":generateViaductPgPersistenceSchemaContributions")?.outcome)
        assertFalse(contribution(directory).exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["@resolver", "@resolver(isSelective: false)"])
    fun `nonselective node resolvers fail validation`(directive: String) {
        prepareConsumer(":")
        val schema = directory.resolve("src/main/viaduct/schema/Group.graphqls")
        schema.writeText(schema.readText().replace("@resolver(isSelective: true, isBatching: true)", directive))
        val result = runGradleAndFail("validateViaductPgPersistenceSchema")
        assertContains(result.output, "Persistent Node 'Group' requires @resolver(isSelective: true)")
    }

    @Test
    fun `separate module validates source and policy changes without altering other modules`() {
        val module = prepareConsumer(":groups")
        directory.resolve("build.gradle.kts").appendText("\napply(plugin = \"dev.viaduct.pg-persistence\")\n")
        // OtherNode intentionally has no persistence; publish its ownership and exclusion explicitly.
        directory.resolve("other/build.gradle.kts").appendText("\napply(plugin = \"dev.viaduct.pg-persistence\")\n")
        directory.resolve("other/src/main/viaduct/pg-persistence.yaml").writeText(
            "types:\n  OtherNode:\n    excluded: true\n",
        )
        val schema = module.resolve("src/main/viaduct/schema/Group.graphqls")
        schema.writeText(schema.readText().replace(" @resolver(isSelective: true, isBatching: true)", ""))
        runGradle(":groups:test", ":generateViaductGRTs")
        val packaged = directory.resolve("build/viaduct/centralSchema/partition/groups/graphql/Group.graphqls")
        assertEquals(schema.readText(), packaged.readText())
        assertContains(
            contribution(module).readText(),
            "extend type Group @resolver(isSelective: true, isBatching: true)",
        )
        val other = directory.resolve("build/viaduct/centralSchema/partition/other/graphql/Other.graphqls")
        assertFalse(other.readText().contains("isSelective"))

        val source = module.resolve("src/main/viaduct/schema/External.graphqls")
        source.writeText("type External implements Node @resolver { id: ID! }")
        val failure = runGradleAndFail(":groups:validateViaductPgPersistenceSchema")
        assertContains(failure.output, "Persistent Node 'External' requires @resolver(isSelective: true)")
        source.writeText("type External implements Node @resolver(isSelective: true) { id: ID! }")
        runGradle(":groups:validateViaductPgPersistenceSchema")

        source.writeText("type External implements Node { id: ID! }")
        module
            .resolve("src/main/viaduct/pg-persistence.yaml")
            .writeText("types:\n  External:\n    excluded: true\n")
        runGradle(":groups:validateViaductPgPersistenceSchema")
        assertFalse(source.readText().contains("isSelective"))
    }

    private fun prepareConsumer(modulePath: String): File {
        writeSettings(modulePath)
        directory.resolve("gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx1g\nviaductVersion=${System.getProperty("consumerViaductVersion")}\n",
        )
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
                    maven("https://central.sonatype.com/repository/maven-snapshots/")
                    gradlePluginPortal()
                }
            }
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }
            dependencyResolutionManagement {
                repositories {
                    maven("https://central.sonatype.com/repository/maven-snapshots/")
                    mavenCentral()
                }
            }
            gradle.beforeProject {
                configurations.configureEach {
                    resolutionStrategy.eachDependency {
                        if (requested.group.startsWith("com.airbnb.viaduct")) {
                            val coherentVersion =
                                if (requested.group == "com.airbnb.viaduct.gradle" && requested.name == "metamodule") {
                                    "2.1.0-20260921.062359-3"
                                } else {
                                    providers.gradleProperty("viaductVersion").get()
                                }
                            useVersion(coherentVersion)
                        }
                    }
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
                testImplementation("io.mockk:mockk:1.13.16")
            }
            tasks.test {
                useJUnitPlatform()
                testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                filter {
                    excludeTestsMatching(
                        "com.example.groups.PersistenceExecutionTest." +
                            "composed mutation joins an immediate transaction and returns its payload"
                    )
                }
            }
            tasks.register<Test>("transactionTest") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
                useJUnitPlatform()
                filter {
                    includeTestsMatching(
                        "com.example.groups.PersistenceExecutionTest." +
                            "composed mutation joins an immediate transaction and returns its payload"
                    )
                }
                shouldRunAfter(tasks.test)
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
        val packaged = directory.resolve("build/viaduct/centralSchema/partition/groups/graphql/Group.graphqls")
        assertEquals(source, packaged.readText())
    }

    private fun contribution(module: File) =
        module.resolve("build/generated/viaduct-persistence-schema-contributions/pg-persistence.graphqls")
}
