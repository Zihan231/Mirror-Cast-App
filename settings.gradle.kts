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

// Modules are added as their steps land: :core-common, :app-receiver.
include(":core-signaling")
include(":core-webrtc")
include(":app-sender")
