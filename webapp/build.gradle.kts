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
