pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
val localProps = java.util.Properties().apply {
    val f = File("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext from Github Packages
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .getOrElse(localProps.getProperty("gpr.user", System.getenv("USERNAME")))
                password = providers.gradleProperty("gpr.key")
                    .getOrElse(localProps.getProperty("gpr.key", System.getenv("TOKEN")))
            }
        }
    }
}

rootProject.name = "Karoo Extension Template"
include("app")
