plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
    id("dev.viaduct.pg-persistence")
}
dependencies {
    implementation(project(":common"))
    implementation("com.airbnb.viaduct:api:${libs.versions.viaduct.get()}")
    implementation("dev.viaduct.persistence:runtime:0.1.0-SNAPSHOT")
}
