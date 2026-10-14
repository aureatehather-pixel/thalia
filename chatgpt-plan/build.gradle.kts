plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:chatgpt-plan` is the isolated feasibility proof for using a ChatGPT plan through Sign in with ChatGPT, and for spoken
// replies. Plain Kotlin on the JVM so every test runs without Android and without the network: the platform pieces (browser,
// Keystore, text-to-speech) reach it through small interfaces. Only the chatgptCheck build type includes it; no shipped
// build does. It is a proof, not an architecture decision. See docs/slice6-chatgpt-plan-proof.md.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core"))
    // The provider, session, wire format and credentials live in the production module; this proof builds on them.
    api(project(":chatgpt-provider"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
