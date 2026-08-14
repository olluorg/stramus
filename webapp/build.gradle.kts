import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

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
            }
        }
        // Kotlin/JS's own default output works too, but the bundle has emitted ES modules since the
        // wa-sqlite days and there is no reason to churn it now that kidx replaced that engine. This
        // also pins the es2015 compilation target.
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

/*
 * No source map in the production bundle — see the extension's build for the reasoning, which applies
 * here with one addition: `pages.yml` publishes this whole directory, so unlike the extension's (which
 * `release.yml` at least strips from the ZIP) this map was actually being served, five megabytes of it,
 * to anyone who opened the site. The development build keeps its map.
 */
tasks.named<KotlinWebpack>("jsBrowserProductionWebpack") {
    sourceMaps = false
}
