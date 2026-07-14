plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    // The wire format, and nothing else: the browser (web app and extension) speaks it, the server
    // speaks it, and neither depends on the other to do so.
    js { browser() }
    jvm()

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
    }
}
