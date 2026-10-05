import java.io.File

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

kotlin { jvmToolchain(21) }
ktlint { version.set("1.5.0") }

dependencies {
    testImplementation(files(providers.gradleProperty("persistenceTestClasspath").map { it.split(File.pathSeparator) }))
}

tasks.test {
    useJUnitPlatform()
    doFirst { systemProperty("workerClasspath", classpath.asPath) }
}
