pluginManagement {
    repositories {
        google()
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

rootProject.name = "Sennachie for Plex"
include(":app")
// Ticket 141: the Wear OS companion and the pure protocol it shares with the phone app.
include(":wear")
include(":wear-shared")
