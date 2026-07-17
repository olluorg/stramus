buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // Applied by each module via `kotlin("multiplatform")` (no per-module version). Matches the
        // Kotlin version Kormium and the kotlin-wrappers 2026.7.1 BOM are built against.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        // The wire format between the client and the server (`protocol`, `server`).
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
