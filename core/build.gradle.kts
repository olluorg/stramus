plugins {
    kotlin("multiplatform")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    js {
        browser()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                // Kormium DSL + the Kotlin/JS browser SQLite engine (composite build → local korm).
                api("io.github.kormium:kormium-core:0.11.0")
                api("io.github.kormium:kormium-sqlite-js:0.11.0")
                // `api`: the AI assistant streams its answer as a Flow, so the type is part of what
                // this module hands to the UI, not merely something it uses inside.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
    }
}
