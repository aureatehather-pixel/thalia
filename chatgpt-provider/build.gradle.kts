plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

// `:chatgpt-provider` is the production-side ChatGPT-plan provider: a ConversationProvider over the Responses API, the signed-in
// session and its renewal, and the sealed-credential interface. Plain Kotlin on the JVM, so every check runs without Android and
// without the network (OkHttp interceptors and MockWebServer stand in for the service). It holds no sign-in flow, no proof
// fixtures and no planning data. No shipped build depends on it yet: the :app dependency is debug and test only, because a release
// build has no INTERNET permission and no OkHttp (ReleaseIsolationTest). See docs/slice6-chatgpt-plan-proof.md section 7k.
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

    // The fake ChatGPT service and sample credentials, shared with :app's tests. Never on a shipped classpath.
    testFixturesImplementation(libs.okhttp)
    testFixturesApi(libs.kotlin.test)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
