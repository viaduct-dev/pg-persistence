plugins { `java-library`; alias(libs.plugins.kotlinJvm) }
dependencies {
    api("com.airbnb.viaduct:api:${libs.versions.viaduct.get()}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
