rootProject.name = "ym_morphe"

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "2.1.0"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

settings {
    extensions {
        defaultNamespace = "app.morphe.extension"
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

// Optional local builds, used here without GitHub Packages credentials.
mapOf("morphe-patcher" to "app.morphe:morphe-patcher").forEach { (path, module) ->
    val dir = file("../$path")
    if (dir.exists()) includeBuild(dir) { dependencySubstitution { substitute(module(module)).using(project(":")) } }
}
file("../morphe-patches-library").let { dir ->
    if (dir.exists()) includeBuild(dir) {
        dependencySubstitution {
            substitute(module("app.morphe:morphe-patches-library")).using(project(":patch-library"))
            substitute(module("app.morphe:morphe-extensions-library")).using(project(":extension-library"))
        }
    }
}
