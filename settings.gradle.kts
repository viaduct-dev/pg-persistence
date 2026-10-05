pluginManagement {
    val viaductVersion: String by settings

    repositories {
        if (viaductVersion.endsWith("-SNAPSHOT")) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val viaductVersion: String by settings

    repositories {
        if (viaductVersion.endsWith("-SNAPSHOT")) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "pg-persistence"

include(
    ":runtime",
    ":plugin",
    ":jdbc",
)
