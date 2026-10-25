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

rootProject.name = "mirror"

// :core-common is added when a step needs shared Android-free helpers.
include(":core-signaling")
include(":core-webrtc")
include(":app-sender")
include(":app-receiver")
