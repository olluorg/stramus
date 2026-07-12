plugins {
    kotlin("multiplatform")
}

repositories {
    google()
    mavenCentral()
}

// Shared React UI for both entry points (webapp + extension). Exposes the kotlin-wrappers React
// stack as `api` so the thin per-app `main()` (createRoot) compiles without re-declaring them.
kotlin {
    js {
        browser()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                api(project(":core"))

                api(project.dependencies.enforcedPlatform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:2026.7.1"))
                api("org.jetbrains.kotlin-wrappers:kotlin-react")
                api("org.jetbrains.kotlin-wrappers:kotlin-react-dom")
                api("org.jetbrains.kotlin-wrappers:kotlin-browser")

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
    }
}
