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

rootProject.name = "Camera"

include(
    ":app",
    ":core-model",
    ":camera-api",
    ":camera-capability",
    ":camera-camera2",
    ":processing-api",
    ":processing-raw",
    ":video-engine",
    ":feature-camera",
    ":feature-settings",
)
