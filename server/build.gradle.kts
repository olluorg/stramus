plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("stramus.server.MainKt")
}

val ktorVersion = "3.5.0"

dependencies {
    implementation(project(":protocol"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Kormium: the DSL, the SQLite engine, the migration journal, and `call.transaction<Db, _> { }`
    // resolving the database out of Ktor's own DI container.
    implementation("io.github.kormium:kormium-core:0.11.0")
    implementation("io.github.kormium:kormium-sqlite:0.11.0")
    implementation("io.github.kormium:kormium-migrate:0.11.0")
    implementation("io.github.kormium:kormium-ktor-di:0.11.0")

    // Google's public keys, fetched and cached, to check the signature on an ID token.
    implementation("com.auth0:jwks-rsa:0.22.1")

    // Argon2id, in pure Java — no JNA, no native library to ship in the container.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Mail, over SMTP. Not a provider's HTTP API: every mail service speaks SMTP, so this works with
    // Postmark, SES, Fastmail or a self-hosted relay without a line of code changing.
    implementation("org.eclipse.angus:angus-mail:2.0.3")

    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    // The client's store and sync engine, exercised against this server over real HTTP: the merge is the
    // one thing in the system that only exists in the space *between* the two, and that is where it is
    // tested. (The client is Kotlin/JS in the app; its code is common, so the JVM can run it here.)
    //
    // Conditional because the server's container image copies in only `server` and `protocol` — there is no
    // browser side there to depend on, and no tests are run there either. Everywhere a person or CI builds,
    // the module is present and this binds.
    if (findProject(":core") != null) {
        testImplementation(project(":core"))
    }
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // A real SMTP server, in the test JVM: the only way to find out whether what we send is what arrives.
    testImplementation("com.icegreen:greenmail:2.1.3")
}

tasks.test {
    useJUnitPlatform()
}
