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

rootProject.name = "StatePilot"

include(":domain")
include(":data")
include(":calendar")
include(":wear-protocol")
include(":app-phone")
include(":app-wear")
include(":persistence")
