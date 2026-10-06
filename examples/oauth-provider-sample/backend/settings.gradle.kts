pluginManagement {
    val persistenceSource = System.getenv("PG_PERSISTENCE_SOURCE") ?: "../../.."
    includeBuild(persistenceSource)
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
    val viaductVersion: String by settings
    plugins { id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion }
}
buildscript {
    val viaductVersion: String by settings
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("com.airbnb.viaduct")) {
                useVersion(if (requested.group == "com.airbnb.viaduct.gradle" && requested.name == "metamodule")
                    "2.1.0-20260921.062359-3" else viaductVersion)
            }
        }
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
gradle.beforeProject {
    // Plugin classpaths and runtime dependencies must use the same Viaduct release.
    for (container in listOf(buildscript.configurations, configurations)) {
        container.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group.startsWith("com.airbnb.viaduct")) {
                    useVersion(if (requested.group == "com.airbnb.viaduct.gradle" && requested.name == "metamodule")
                        "2.1.0-20260921.062359-3" else viaductVersion)
                }
            }
        }
    }
}
rootProject.name = "oauth-provider-sample"
include(":common")
includeViaductApplication {
    project(":")
    modulePackagePrefix("com.example")
    includeModule { project(":database"); modulePackageSuffix("database") }
    includeModule { project(":provider"); modulePackageSuffix("provider") }
}
