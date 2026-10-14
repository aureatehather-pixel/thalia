plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:voice-gemini` is the Gemini Live provider behind `:core`'s VoiceSessionClient.
// It is plain Kotlin on the JVM: the microphone and speaker reach it only through its
// AudioIo interface, so every test here runs without Android. OkHttp stays an
// implementation detail; nothing in the public API names an OkHttp type.
// See docs/adr/0008-m3a-manual-gemini-live-spike.md.
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
