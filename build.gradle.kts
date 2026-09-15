plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("com.github.spotbugs") version "6.5.11" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT")

allprojects {
    group = "dev.viaduct.persistence"
    version = releaseVersion.get()
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
    }

    configure<com.github.spotbugs.snom.SpotBugsExtension> {
        ignoreFailures.set(false)
        excludeFilter.set(
            rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"),
        )
    }

    tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
        reports.create("xml") {
            required.set(true)
        }
    }

    plugins.withId("base") {
        tasks.named("check") {
            dependsOn("ktlintCheck", "detekt", "spotbugsMain", "spotbugsTest")
        }
    }

    plugins.withId("maven-publish") {
        pluginManager.apply("signing")

        pluginManager.withPlugin("java") {
            extensions.configure<JavaPluginExtension> {
                withSourcesJar()
                withJavadocJar()
            }
        }

        val signingKeyId = providers.gradleProperty("signingKeyId").orNull
        val signingKey = providers.gradleProperty("signingKey").orNull
        val signingPassword = providers.gradleProperty("signingPassword").orNull

        extensions.configure<org.gradle.plugins.signing.SigningExtension> {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
            setRequired {
                !project.version.toString().endsWith("-SNAPSHOT") &&
                    gradle.taskGraph.allTasks.any {
                        it is org.gradle.api.publish.maven.tasks.PublishToMavenRepository
                    }
            }
            sign(extensions.getByType<PublishingExtension>().publications)
        }

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("PG Persistence: ${project.name}")
                    description.set(
                        "Schema-first PostgreSQL persistence tooling for Viaduct GraphQL applications"
                    )
                    url.set("https://github.com/viaduct-dev/pg-persistence")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("viaduct-dev")
                            name.set("Viaduct Developers")
                            url.set("https://github.com/viaduct-dev")
                        }
                    }
                    scm {
                        connection.set(
                            "scm:git:git://github.com/viaduct-dev/pg-persistence.git"
                        )
                        developerConnection.set(
                            "scm:git:ssh://github.com/viaduct-dev/pg-persistence.git"
                        )
                        url.set("https://github.com/viaduct-dev/pg-persistence")
                    }
                }
            }

            repositories {
                maven {
                    name = "isolated"
                    url = uri(
                        providers.gradleProperty("isolatedRepository")
                            .orElse(rootProject.layout.buildDirectory.dir("repository").map {
                                it.asFile.absolutePath
                            })
                            .get()
                    )
                }

                if (
                    providers.gradleProperty("publishCentralSnapshots")
                        .map(String::toBoolean)
                        .getOrElse(false)
                ) {
                    maven {
                        name = "centralSnapshots"
                        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                        credentials {
                            username = providers.gradleProperty("mavenCentralUsername").orNull
                            password = providers.gradleProperty("mavenCentralPassword").orNull
                        }
                    }
                }
            }
        }
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
