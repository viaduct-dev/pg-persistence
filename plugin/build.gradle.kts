plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val viaductVersion: String by project

dependencies {
    implementation(project(":runtime"))
    implementation("com.airbnb.viaduct:buildtime:$viaductVersion")
    implementation("com.airbnb.viaduct.gradle:metamodule:$viaductVersion") {
        isTransitive = false
    }
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")
    implementation("org.hibernate.orm:hibernate-core:7.3.4.Final")
    implementation("org.liquibase:liquibase-core:5.0.3")
    implementation("org.liquibase.ext:liquibase-hibernate7:5.0.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    implementation("org.antlr:ST4:4.3.1")
    implementation("org.yaml:snakeyaml:2.6")
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.airbnb.viaduct:test-fixtures:$viaductVersion")
    testImplementation("io.ktor:ktor-client-mock:3.2.0")
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
}

gradlePlugin {
    plugins {
        create("viaductPgPersistence") {
            id = "dev.viaduct.pg-persistence"
            implementationClass =
                "dev.viaduct.persistence.gradle.ViaductPgPersistencePlugin"
            displayName = "PG Persistence"
            description =
                "Generates Hibernate metadata and database review artifacts from Viaduct GraphQL"
        }
    }
}

val consumerPlugins by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
}
val consumerRuntime by configurations.creating

dependencies {
    consumerRuntime(project(":runtime"))
    consumerPlugins("com.airbnb.viaduct.gradle:settings:$viaductVersion")
    consumerPlugins("com.airbnb.viaduct.gradle:application:$viaductVersion")
    consumerPlugins("com.airbnb.viaduct.gradle:module:$viaductVersion")
    consumerPlugins("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.0-1.0.29")
}

fun Test.configureConsumerClasspath() {
    inputs.files(consumerRuntime, consumerPlugins)
    inputs.property("consumerViaductVersion", viaductVersion)
    doFirst {
        systemProperty("consumerRuntimeClasspath", consumerRuntime.asPath)
        systemProperty("consumerViaductVersion", viaductVersion)
        systemProperty("consumerPluginClasspath", consumerPlugins.asPath)
    }
}

tasks.test {
    exclude("**/SelectiveNodePluginTest.class")
    configureConsumerClasspath()
}

val selectiveNodePluginExecutionTest =
    tasks.register<Test>("selectiveNodePluginExecutionTest") {
        description = "Runs the selective-node execution integration test in an isolated JVM"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching(
                "dev.viaduct.persistence.gradle.SelectiveNodePluginTest." +
                    "single project compiles and executes explicitly declared selective resolvers",
            )
            includeTestsMatching(
                "dev.viaduct.persistence.gradle.SelectiveNodePluginTest." +
                    "node implementation compiles and executes selectively without schema annotations",
            )
        }
        configureConsumerClasspath()
        shouldRunAfter(tasks.test)
    }

val selectiveNodePluginValidationTest =
    tasks.register<Test>("selectiveNodePluginValidationTest") {
        description = "Runs selective-node validation integration tests in an isolated JVM"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching("dev.viaduct.persistence.gradle.SelectiveNodePluginTest.*")
            excludeTestsMatching(
                "dev.viaduct.persistence.gradle.SelectiveNodePluginTest." +
                    "single project compiles and executes explicitly declared selective resolvers",
            )
            excludeTestsMatching(
                "dev.viaduct.persistence.gradle.SelectiveNodePluginTest." +
                    "node implementation compiles and executes selectively without schema annotations",
            )
        }
        configureConsumerClasspath()
        shouldRunAfter(selectiveNodePluginExecutionTest)
    }

tasks.check {
    dependsOn(selectiveNodePluginExecutionTest, selectiveNodePluginValidationTest)
}
