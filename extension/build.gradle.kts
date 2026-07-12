plugins {
    kotlin("multiplatform")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    js {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "stramus.js"
                // MV3 CSP forbids 'unsafe-eval'. Webpack's development mode defaults to
                // devtool = "eval", wrapping every module in eval() — rejected by the extension page.
                // Force a non-eval devtool so even development builds are loadable as an extension.
                devtool = "source-map"
            }
        }
        // wa-sqlite (via kormium-sqlite-js) is ESM-only; the final bundle emits ES modules.
        useEsModules()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":ui-shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
    }
}
