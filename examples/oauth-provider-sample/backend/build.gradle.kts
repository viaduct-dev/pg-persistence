plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
    application
}
dependencies {
    implementation(project(":common"))
    implementation(project(":database"))
    implementation(project(":provider"))
    implementation("com.airbnb.viaduct:runtime:${libs.versions.viaduct.get()}")
    runtimeOnly("com.airbnb.viaduct:buildtime:${libs.versions.viaduct.get()}")
    implementation("io.ktor:ktor-server-cio:3.2.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.0")
    implementation("io.ktor:ktor-serialization-jackson:3.2.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("dev.viaduct.persistence:jdbc:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
    implementation("javax.inject:javax.inject:1")
    testImplementation("io.ktor:ktor-server-test-host:3.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
application { mainClass.set("com.example.MainKt") }
tasks.test {
    useJUnitPlatform()
    dependsOn(":database:buildViaductEffectiveModel")
    systemProperty("sample.schemaSql", project(":database").layout.buildDirectory.file("generated/viaduct-effective-model/META-INF/schema-create.sql").get().asFile.absolutePath)
}
tasks.withType<JavaExec> { jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED") }
tasks.register<JavaExec>("installSchema") {
    dependsOn("classes", ":database:buildViaductEffectiveModel")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.example.SchemaInstallerKt")
    args(project(":database").layout.buildDirectory.file("generated/viaduct-effective-model/META-INF/schema-create.sql").get().asFile.absolutePath)
}
