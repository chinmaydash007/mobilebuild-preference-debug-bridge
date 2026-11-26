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
        val validationRepository = providers.gradleProperty("validationRepository").orNull
        if (validationRepository != null) {
            maven { url = uri(validationRepository) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "preference-bridge-consumer-fixture"
include(":app")
