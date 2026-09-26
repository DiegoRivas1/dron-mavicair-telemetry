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
        // com.dji:dji-sdk y dji-sdk-provided están publicados en Maven Central
        mavenCentral()
    }
}

rootProject.name = "MavicAirTelemetry"
include(":app")
