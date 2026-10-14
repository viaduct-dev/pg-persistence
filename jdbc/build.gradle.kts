plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.postgresql:postgresql:42.7.5")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("io.ktor:ktor-client-cio:3.2.0")
}

// The module's only Java class is package-private; its public API is written in Kotlin.
tasks.javadoc {
    options.memberLevel = JavadocMemberLevel.PACKAGE
}

tasks.test {
    // Transport/role changes must rerun the live contract rather than reuse a JDBC-only result.
    listOf("PG_JDBC_TEST_URL", "PG_JDBC_TEST_ADMIN_USER", "PG_CROSS_SCHEMA_HTTP_URL").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
