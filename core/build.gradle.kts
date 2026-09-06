import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

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
                api("io.github.kormium:kidx:0.1.1")

                // Full-text card search — kidx deliberately has none; this is its companion in-memory
                // BM25 index, kept fresh from `Cards.observe(db)` via kromus-sync's `syncTo`.
                api("io.github.kormium:kromus-core:0.15.0")
                implementation("io.github.kormium:kromus-sync:0.15.0")

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
                // A scripted HTTP engine, so the api's own behaviour — refreshing an access token that
                // has run out, and doing it once rather than per call — is testable without a clock.
                implementation("io.ktor:ktor-client-mock:3.5.0")
            }
        }
    }
}

/*
 * The live sync tests need a server to talk to, so the build starts one.
 *
 * `LiveServerTest` drives the real client — the same `SyncEngine` and `StramusApi` the browser runs —
 * over real HTTP against the real Ktor server on a real SQLite file. Nothing else reaches that far:
 * `ContractTest` has the real client and the real decisions but no transport, and the server's own
 * end-to-end tests have the transport and a hand-rolled client.
 *
 * Opt-in (`-PliveSync`) because it starts a process and binds a port, which is not something every
 * `./gradlew check` on a laptop should do without being asked. CI passes it.
 */
val liveSync = providers.gradleProperty("liveSync").isPresent
val liveSyncPort = (providers.gradleProperty("liveSyncPort").orNull ?: "8099").toInt()

if (liveSync) {
    var serverProcess: Process? = null

    tasks.named("jsNodeTest") {
        // By path, not by task object: `:server` is not configured yet when this one is, and asking it
        // for a task here fails before the build has even started.
        dependsOn(":server:installDist")
        val dbDir = layout.buildDirectory.dir("live-sync").get().asFile
        val startScript = rootProject.layout.projectDirectory
            .file("server/build/install/server/bin/server").asFile

        doFirst {
            dbDir.deleteRecursively()
            dbDir.mkdirs()
            serverProcess = ProcessBuilder(startScript.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(dbDir, "server.log"))
                .apply {
                    environment()["PORT"] = liveSyncPort.toString()
                    environment()["STRAMUS_DB"] = File(dbDir, "live.db").absolutePath
                    environment()["STRAMUS_BLOBS"] = File(dbDir, "blobs").absolutePath
                    // The doors these tests come in by. A default server answers them 501.
                    environment()["STRAMUS_EMAIL_AUTH"] = "1"
                }
                .start()

            // Wait for it to answer rather than guess at a sleep: a fixed pause is either too short on a
            // cold machine or wasted on a warm one.
            val deadline = System.currentTimeMillis() + 60_000
            var up = false
            while (!up && System.currentTimeMillis() < deadline) {
                up = runCatching {
                    Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", liveSyncPort), 500) }
                    true
                }.getOrDefault(false)
                if (!up) Thread.sleep(250)
            }
            check(up) { "the sync server did not come up on $liveSyncPort — see ${File(dbDir, "server.log")}" }
        }

        doLast {
            serverProcess?.destroy()
            serverProcess?.waitFor(10, TimeUnit.SECONDS)
            serverProcess?.destroyForcibly()
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
        environment("STRAMUS_LIVE_URL", "http://127.0.0.1:$liveSyncPort")
    }
}
