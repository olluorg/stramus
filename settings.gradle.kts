pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stramus"

// Kormium is developed in the sibling checkout and consumed as a composite build, so local edits
// to it (e.g. kormium-sqlite-js) are picked up live without publishing. Dependency coordinates
// io.github.kormium:* are substituted automatically for the included build's projects.
// CI (and any clone without the sibling checkout) has no ../korm and resolves the published
// io.github.kormium:*:0.11.0 artifacts from Maven Central instead — so include it only when present.
// Still used by the server, which stays on Kormium/SQLite (kidx is browser-only, see below).
if (file("../korm").isDirectory) {
    includeBuild("../korm")
}

// kidx and kromus are developed in sibling checkouts the same way. Both publish under the
// io.github.kormium group too, so the same coordinate substitution applies. kidx replaces
// kormium-sqlite-js for the browser-side store (native IndexedDB instead of SQLite-on-WASM);
// kromus (kromus-core/kromus-sync) supplies the full-text search kidx deliberately leaves out.
if (file("../kidx").isDirectory) {
    includeBuild("../kidx")
}
if (file("../kromus").isDirectory) {
    includeBuild("../kromus")
}

// The server and the wire format it speaks are always here. The browser side — everything below — is
// included only when its source is actually present, because the server's container image copies in just
// these two: building the Kotlin/JS modules there would pull down a whole Node toolchain to produce a
// bundle the image does not serve.
include("protocol", "server")

listOf("core", "ui-shared", "webapp", "extension")
    .filter { file(it).isDirectory }
    .forEach { include(it) }
