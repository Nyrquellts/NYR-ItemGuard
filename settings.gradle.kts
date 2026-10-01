// NYR Item Guard, part of the NYR Fixes line: one plugin, its jar and its download.
//
//   ./gradlew build          the plugin's jar, tests included
//   ./gradlew saleZips       the download, once the jar has passed its jar test and API linkage check

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nyr-item-guard"

include("common", "linkage", "illegal")
