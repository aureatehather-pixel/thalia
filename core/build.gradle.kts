plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:core` is deliberately plain Kotlin/JVM. It must never depend on the Android
// SDK, on an AI provider SDK, or on a network client. See docs/adr/0002.
// A JSON library is none of those.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // The planning export's payload, ADR 0009 decision 8. Only the JSON element API is used, so the
    // serialization compiler plugin is not applied.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
