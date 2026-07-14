pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "stramus"

// Kormium is developed in the sibling checkout and consumed as a composite build, so local edits
// to it (e.g. kormium-sqlite-js) are picked up live without publishing. Dependency coordinates
// io.github.kormium:* are substituted automatically for the included build's projects.
// CI (and any clone without the sibling checkout) has no ../korm and resolves the published
// io.github.kormium:*:0.11.0 artifacts from Maven Central instead — so include it only when present.
if (file("../korm").isDirectory) {
    includeBuild("../korm")
}

include("core", "ui-shared", "webapp", "extension", "protocol", "server")
