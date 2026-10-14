plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
}
dependencies {
    implementation(project(":common"))
    implementation("com.airbnb.viaduct:api:${libs.versions.viaduct.get()}")
}
