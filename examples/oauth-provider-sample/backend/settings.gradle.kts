pluginManagement {
    val persistenceSource = System.getenv("PG_PERSISTENCE_SOURCE") ?: "../../.."
    includeBuild(persistenceSource)
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
    val viaductVersion: String by settings
    plugins { id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion }
}
buildscript {
    val viaductVersion: String by settings
    fun org.gradle.api.artifacts.ConfigurationContainer.pinViaductVersions() {
        configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group.startsWith("com.airbnb.viaduct")) {
                    useVersion(viaductVersion)
                }
            }
        }
    }
    configurations.pinViaductVersions()
    gradle.beforeProject {
        // Plugin classpaths and runtime dependencies must use the same Viaduct release.
        buildscript.configurations.pinViaductVersions()
        configurations.pinViaductVersions()
    }
}
plugins { id("com.airbnb.viaduct.settings-gradle-plugin") }
includeBuild(System.getenv("PG_PERSISTENCE_SOURCE") ?: "../../..")
val viaductVersion: String by settings
dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
    versionCatalogs { create("libs") { version("viaduct", viaductVersion) } }
}
rootProject.name = "oauth-provider-sample"
include(":common")
includeViaductApplication {
    project(":")
    modulePackagePrefix("com.example")
    includeModule { project(":database"); modulePackageSuffix("database") }
    includeModule { project(":provider"); modulePackageSuffix("provider") }
}
