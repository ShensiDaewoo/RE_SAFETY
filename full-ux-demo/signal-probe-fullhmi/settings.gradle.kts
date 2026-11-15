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
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")   // aurora13: Pleos Vehicle SDK (public repo in the Pleos docs)
    }
}

rootProject.name = "ReSafetyFullHmi"
include(":app")
