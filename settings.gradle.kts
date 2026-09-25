// One module: the Compose app. The Rust bridge is a directory next to it, not a
// Gradle subproject — cargo owns that build and Gradle only consumes its .so.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex(".*android.*")
                includeGroupByRegex(".*google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QuireCompose"
include(":app")
