plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

val spotbugsAnnotations by configurations.creating

dependencies {
    api(project(":runtime"))
    api("org.hibernate.orm:hibernate-core:7.3.4.Final")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    spotbugsAnnotations("org.checkerframework:checker-qual:3.49.2")
}

// Hibernate's inherited type-descriptor annotations are needed only by bytecode analysis.
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    auxClassPaths.from(spotbugsAnnotations)
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
