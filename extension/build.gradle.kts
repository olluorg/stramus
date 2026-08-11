import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    kotlin("multiplatform")
}

repositories {
    google()
    mavenCentral()
}

/**
 * The public half of a keypair generated once for local development and never used for anything else —
 * there is no matching private key kept anywhere, because none is needed. Chrome derives an unpacked
 * extension's id from this field when it is present (rather than from the folder path, which changes it
 * on every reload), so a build that carries this always loads as `dkjnlofmmbaopfdjmkjbolcchlapfnpo` — add
 * `chrome-extension://dkjnlofmmbaopfdjmkjbolcchlapfnpo` to a local server's `STRAMUS_ALLOWED_ORIGINS`
 * once, and it never has to be touched again for the life of that checkout.
 */
val devManifestKey =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh44Gykl2Y9zNmnpm99tUK/TbREZde3nLwfrrSCUhRMVkvamyj07RkzEWVDwD4CEFcbdJAsd2INehMwm0IXTzUHNk1c1HP0nApcGwPeExgk9wH7hdNNAAcAj2cxNZsHzOKX96UDapKbDh95MkY1shvEPzAo0iCAkTHsjAulv+4nZAtGaPkfXOPoOG5C96rr160P1nuMl+TlKvhptIVysbRCMdoxXUMGgxs8XwYQxjzx4WptNdETHDyTzxy3HI5Jkd4du7tqzbKqhx9egPLu+d/D9YkJ6TU2he46bWR7h83FyCHhZoiM6YS6H3Z9yoPei1j8h3l/WUmtaXhPT4c8LlCQIDAQAB"

/** What a local server is reachable at — not in the published manifest's `host_permissions` at all. */
val devServerHostPermission = "http://localhost:8090/*"

/**
 * Patches the *built* `manifest.json` for local development — the checked-in one, and the Web Store
 * listing generated from it, are never touched. Only runs at all when `STRAMUS_DEV=1`; every other build,
 * this task exists but does nothing, which is what keeps the published extension from ever quietly
 * asking for a permission it does not need.
 *
 * Two things change: [DEV_SERVER_HOST_PERMISSION] is added so the extension may call a local server, and
 * [DEV_MANIFEST_KEY] is added so it does so from the same `chrome-extension://` origin every time —
 * without it, an unpacked reload gets a new id and a local server's `STRAMUS_ALLOWED_ORIGINS` has to be
 * hand-edited to match, which is exactly the annoyance this exists to remove.
 */
val patchDevManifest = tasks.register("patchDevManifest") {
    group = "build"
    description = "Adds the local server's origin and a stable id to the built manifest.json (STRAMUS_DEV=1 only)."
    val dev = providers.environmentVariable("STRAMUS_DEV").orNull == "1"
    onlyIf { dev }
    doLast {
        listOf("productionExecutable", "developmentExecutable").forEach { variant ->
            val manifestFile = layout.buildDirectory.file("dist/js/$variant/manifest.json").get().asFile
            if (!manifestFile.exists()) return@forEach
            @Suppress("UNCHECKED_CAST")
            val manifest = JsonSlurper().parse(manifestFile) as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val hostPermissions = (manifest["host_permissions"] as? MutableList<Any?>) ?: mutableListOf()
            if (devServerHostPermission !in hostPermissions) hostPermissions += devServerHostPermission
            manifest["host_permissions"] = hostPermissions
            manifest["key"] = devManifestKey
            manifestFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))
        }
    }
}

// Not the webpack tasks: those only write the bundle into `build/kotlin-webpack/js/$variant/`. The
// manifest this patches lives in `build/dist/js/$variant/`, which the *distribution* tasks assemble —
// copying `processedResources`' manifest.json in fresh — and they run after webpack, so finalizing off
// webpack patched a manifest the next task then overwrote right back to the unpatched one.
tasks.matching { it.name == "jsBrowserDistribution" || it.name == "jsBrowserDevelopmentExecutableDistribution" }
    .configureEach { finalizedBy(patchDevManifest) }

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
        // Kotlin/JS's own default output works too, but the bundle has emitted ES modules since the
        // wa-sqlite days and there is no reason to churn it now that kidx replaced that engine.
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
