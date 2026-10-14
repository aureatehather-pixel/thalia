plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:openai-provider`: OpenAI Responses as a ConversationProvider, and Google Cloud TTS (Chirp 3 HD). Plain JVM, no Android.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // The provider runs on real threads against a real local socket, so its tests use real
    // time rather than a virtual-time test scheduler.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
