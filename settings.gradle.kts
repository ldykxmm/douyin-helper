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
        // Xposed API 不在 Maven Central，需要专用仓库
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "Dou+"
include(":app")
