plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":jdbc"))
    api("dev.dbos:transact:1.0.0")
    implementation("dev.dbos:transact-jdbi-step-factory:1.0.0")

    testImplementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.0"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.apiguardian:apiguardian-api:1.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.postgresql:postgresql:42.7.11")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

// DBOS brings a newer Kotlin standard library. Verify a consumer with its compiler independently.
val compatibilityClasspath = files(tasks.jar, configurations.testRuntimeClasspath)
val kotlinCompatibilityTest by tasks.registering(GradleBuild::class) {
    group = "verification"
    dependsOn(compatibilityClasspath)
    dir = file("src/compatibilityTest")
    tasks = listOf("check")
    doFirst {
        startParameter.projectProperties = mapOf("persistenceTestClasspath" to compatibilityClasspath.asPath)
    }
}

tasks.check { dependsOn(kotlinCompatibilityTest) }

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
