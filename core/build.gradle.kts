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
        // Node runs the store/sync test suite against `fake-indexeddb` — kidx has no JVM target, so
        // this is the only place that logic can be tested without a real browser.
        nodejs {
            testTask { useMocha { timeout = "30s" } }
        }
    }

    // The app itself only ever runs in a browser. The JVM target carries no product code — it is here
    // so the parts of `core` with no browser in them (the ordering keys, the rules the sync engine
    // merges by) can be put under a test, which is what a Kotlin/JS-only module makes awkward.
    jvm()

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                // The wire format the sync engine speaks. Shared with the server, which speaks it back.
                api(project(":protocol"))
                // `api`: the AI assistant streams its answer as a Flow, so the type is part of what
                // this module hands to the UI, not merely something it uses inside.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }

        // No jvmTest here any more: kidx targets js/wasmJs only (it's a layer over IndexedDB, which
        // does not exist on the JVM), so the store, the sync engine and their schema live in jsMain
        // and are exercised in jsTest, under fake-indexeddb, the way kidx tests itself.
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        val jsMain by getting {
            dependencies {
                // The browser store: a typed layer directly over IndexedDB (composite build → local
                // kidx when ../kidx is checked out). No SQLite, no WASM.
                api("io.github.kormium:kidx:0.1.0")

                // Full-text card search — kidx deliberately has none; this is its companion in-memory
                // BM25 index, kept fresh from `Cards.observe(db)` via kromus-sync's `syncTo`.
                api("io.github.kormium:kromus-core:0.14.0")
                implementation("io.github.kormium:kromus-sync:0.14.0")

                // The server, over HTTP. Only the browser talks to it — the engine itself takes a
                // `SyncApi`, which is why it can be tested against the real server without one.
                implementation("io.ktor:ktor-client-core:3.5.0")
                implementation("io.ktor:ktor-client-js:3.5.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                // IndexedDB itself, for a database the store/sync tests can actually open under Node.
                implementation(npm("fake-indexeddb", "^6.0.0"))
            }
        }
    }
}
