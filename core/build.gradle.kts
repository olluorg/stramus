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

    // The app itself only ever runs in a browser. The JVM target carries no product code — it is here
    // so the parts of `core` with no browser in them (the ordering keys, the rules the sync engine
    // merges by) can be put under a test, which is what a Kotlin/JS-only module makes awkward.
    jvm()

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kormium's DSL and the schema are platform-independent; the engine under them is not.
                api("io.github.kormium:kormium-core:0.11.0")
                // `api`: the AI assistant streams its answer as a Flow, so the type is part of what
                // this module hands to the UI, not merely something it uses inside.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }

        // Tests live on the JVM alone: the code they cover is common, and a browser test run would
        // want Karma and a Chrome to point it at for no gain. The store itself is exercised here too —
        // against SQLite on a file, which is the same SQLite the browser runs, only reachable.
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.github.kormium:kormium-sqlite:0.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        val jsMain by getting {
            dependencies {
                // The browser SQLite engine (composite build → local korm when ../korm is checked out).
                api("io.github.kormium:kormium-sqlite-js:0.11.0")
            }
        }
    }
}
