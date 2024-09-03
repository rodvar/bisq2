pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../../build-logic")
}

plugins {
    id("bisq.gradle.toolchain_resolver.ToolchainJreResolverPlugin")
}

toolchainManagement {
    jvm {
        javaRepositories {
            repository("bisq_zulu") {
                resolverClass.set(bisq.gradle.toolchain_resolver.BisqJreToolchainResolver::class.java)
            }
        }
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

includeBuild("../..")

include("desktop")
include("desktop-app")
include("desktop-app-launcher")
include("webcam-app")

rootProject.name = "desktop"
