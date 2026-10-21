pluginManagement {
    repositories {
        System.getenv("VIADUCT_MAVEN_REPOSITORY")?.let { repository ->
            exclusiveContent {
                forRepository { maven(repository) }
                filter { includeGroupByRegex("com\\.airbnb\\.viaduct.*") }
            }
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        System.getenv("VIADUCT_MAVEN_REPOSITORY")?.let { repository ->
            exclusiveContent {
                forRepository { maven(repository) }
                filter { includeGroupByRegex("com\\.airbnb\\.viaduct.*") }
            }
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "pg-persistence"

include(
    ":runtime",
    ":plugin",
    ":jdbc",
    ":dbos",
)
