plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val viaductVersion: String by project

dependencies {
    implementation(project(":runtime"))
    implementation("com.airbnb.viaduct:buildtime:$viaductVersion")
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
    testImplementation("com.h2database:h2:2.3.232")
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
