import com.android.build.api.variant.HostTestBuilder

// AGP 9 compiles Kotlin itself, so org.jetbrains.kotlin.android must NOT be applied
// here; doing so is now an error rather than a redundancy. The Compose compiler is
// still a separate Kotlin plugin and is still required.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.thalia.app"

    // Compose 1.12 and core-ktx 1.19 are built against API 37 and refuse to compile
    // against less. compileSdk only widens the API surface available at build time; it
    // changes no runtime behaviour. The runtime opt-in is targetSdk, below, and stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thalia.app"

        // 31 (Android 12) is where modern exact-alarm and full-screen-intent behaviour
        // begins. There is no legacy install base to support on a personal app, so
        // nothing is gained by carrying pre-12 compatibility branches. See ADR 0001.
        minSdk = 31

        // 36 is Android 16, which the Pixel 10 shipped with. Raising targetSdk to 37
        // opts the app in to Android 17 behaviour changes; that is a deliberate follow-up
        // once M1 has been validated on the device, not something to change blind.
        targetSdk = 36

        versionCode = 1
        versionName = "0.1-m1"

        // Planning storage, ADR 0009. The real app's names; recoveryCheck below uses its own.
        // Changing either for debug or release would make every existing planning.db unreadable.
        buildConfigField("String", "PLANNING_STORAGE_NAME", "\"planning\"")
        buildConfigField("String", "PLANNING_KEY_ALIAS", "\"thalia-planning-db-v1\"")
    }

    buildTypes {
        debug {
            // M1 is a device-validation build. It is installed by hand, never published.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
        // ADR 0009 slice 1B: an isolated app for checking recovery on a device. Its own application
        // id, so its own data directory and Keystore; its own planning file names and Keystore alias;
        // the same production planning code. Its check screen is in src/recoveryCheck only. It never
        // reads or writes com.thalia.app.debug, so that app is never cleared, uninstalled or tested
        // to check recovery. It does not include the M3A spike, which is in src/debug.
        create("recoveryCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".recoverycheck"
            versionNameSuffix = "-recoverycheck"
            matchingFallbacks += "debug"
            buildConfigField("String", "PLANNING_STORAGE_NAME", "\"recoverycheck-planning\"")
            buildConfigField("String", "PLANNING_KEY_ALIAS", "\"thalia-recoverycheck-planning-db-v1\"")
        }
        // ADR 0010: an isolated app for checking the text conversation on a device with a scripted provider. Same pattern
        // as recoveryCheck: its own application id, data directory, Keystore alias and planning files, the production
        // conversation and planning code, and its check screen and scripted provider in src/conversationCheck only.
        // It sends nothing anywhere and never reads or writes com.thalia.app.debug.
        create("conversationCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".conversationcheck"
            versionNameSuffix = "-conversationcheck"
            matchingFallbacks += "debug"
            buildConfigField("String", "PLANNING_STORAGE_NAME", "\"conversationcheck-planning\"")
            buildConfigField("String", "PLANNING_KEY_ALIAS", "\"thalia-conversationcheck-planning-db-v1\"")
        }
        // ADR 0013: the isolated synthetic live-model check. Its own application id, data directory, Keystore alias and
        // planning files, the production conversation, gates and planning code, the one build outside debug that has INTERNET
        // and the Gemini transport, and its launcher and synthetic seed in src/geminiCheck only. It holds no personal data and
        // never reads or writes com.thalia.app.debug. Nothing in it sends a request until the owner enters a key and presses Send.
        create("geminiCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".geminicheck"
            versionNameSuffix = "-geminicheck"
            matchingFallbacks += "debug"
            buildConfigField("String", "PLANNING_STORAGE_NAME", "\"geminicheck-planning\"")
            buildConfigField("String", "PLANNING_KEY_ALIAS", "\"thalia-geminicheck-planning-db-v1\"")
        }
        // The everyday V1 route (OpenAI GPT-6 Luna reasoning, Google Chirp 3 HD Aoede speech) checked on a device with synthetic data.
        // Its own application id, data directory, Keystore alias and planning files; with the debug build type (the real app) the
        // only build that has INTERNET and the OpenAI and speech clients. Its launcher is in src/liveCheck only.
        create("liveCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".livecheck"
            versionNameSuffix = "-livecheck"
            matchingFallbacks += "debug"
            buildConfigField("String", "PLANNING_STORAGE_NAME", "\"livecheck-planning\"")
            buildConfigField("String", "PLANNING_KEY_ALIAS", "\"thalia-livecheck-planning-db-v1\"")
        }
        // Slice 6 feasibility proof (docs/slice6-chatgpt-plan-proof.md): an isolated app for checking Sign in with ChatGPT plan usage
        // and spoken replies on a device. Its own application id, data directory, Keystore alias and planning files; synthetic data
        // only; the one build, with geminiCheck, outside debug that has INTERNET. Its screen is in src/chatgptCheck only. It sends
        // nothing until the owner signs in and presses a button that says it sends, and never reads or writes com.thalia.app.debug.
        create("chatgptCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".chatgptcheck"
            versionNameSuffix = "-chatgptcheck"
            matchingFallbacks += "debug"
            buildConfigField("String", "PLANNING_STORAGE_NAME", "\"chatgptcheck-planning\"")
            buildConfigField("String", "PLANNING_KEY_ALIAS", "\"thalia-chatgptcheck-planning-db-v1\"")
        }
    }

    // The everyday route's clients (src/openaiLive) are compiled by the debug build type, which is the real app, and by liveCheck only.
    sourceSets {
        getByName("debug") { kotlin.directories.add("src/openaiLive/kotlin") }
        getByName("liveCheck") { kotlin.directories.add("src/openaiLive/kotlin") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // AGP 9 defaults these off. Named explicitly so the default is a decision.
        resValues = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources to build a simulated
            // Android for the Room tests.
            isIncludeAndroidResources = true
        }
    }
}

// AGP runs host tests for the debug build type only. The release build's tests are enabled too, so
// src/testRelease can prove the release build carries none of the M3A spike. See ADR 0008.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    // The ChatGPT-plan proof's platform adapters are tested in src/testChatgptCheck.
    beforeVariants(selector().withBuildType("chatgptCheck")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    // The live check build's own guard (send count, run total, stop file) is tested in src/testGeminiCheck.
    beforeVariants(selector().withBuildType("geminiCheck")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// Room writes each schema version it compiles to this directory. It is committed, so a later
// migration can be checked against the exact schema that shipped.
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core"))
    // The M3A Gemini Live spike, and only in debug builds: its code is in src/debug, and a release
    // build has neither this module nor OkHttp on its classpath. See ADR 0008.
    debugImplementation(project(":voice-gemini"))
    // R7: the sealed adapter for the ChatGPT-plan credential interface (src/debug). Nothing installs the route; release has neither.
    debugImplementation(project(":chatgpt-provider"))
    // The everyday V1 route: OpenAI Responses and Google Text-to-Speech clients, the debug build (the real app) and liveCheck only.
    debugImplementation(project(":openai-provider"))
    add("liveCheckImplementation", project(":openai-provider"))
    add("liveCheckImplementation", project(":intent-cases"))
    // ADR 0013: the live check build only. The release, recoveryCheck and conversationCheck builds have neither.
    add("geminiCheckImplementation", project(":voice-gemini"))
    add("geminiCheckImplementation", libs.okhttp)
    // Slice 6 feasibility proof: the chatgptCheck build only. No shipped build has this module, OkHttp or INTERNET through it.
    add("chatgptCheckImplementation", project(":chatgpt-plan"))
    add("chatgptCheckImplementation", libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // The Android plugin does not pick a kotlin-test framework the way the plain JVM
    // plugin in :core does, so the JUnit 5 flavour is named. It brings the same engine.
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

    // Slice 4A: the gated provider is exercised with the real Gemini transport against a local socket (src/testDebug only).
    testDebugImplementation(libs.okhttp)

    // R7: the production ChatGPT-plan provider, exercised end to end with a simulated service (src/testDebug only; the module itself
    // comes in through debugImplementation above). No shipped build depends on it: a release build has no INTERNET permission or
    // OkHttp, and ReleaseIsolationTest keeps it that way.
    testDebugImplementation(testFixtures(project(":chatgpt-provider")))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Robolectric simulates Android inside the test JVM, which needs more than the default heap.
    maxHeapSize = "1g"
    // And on Java 17 and later it reaches into a JDK-internal class to fake file descriptors, which
    // the module system refuses unless the package is exported to it.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

// The chatgptCheck manifest removes the main app's alarm receivers, screens and permissions, so the two suites that assert those
// components exist cannot hold in that variant. They still run, unchanged, for debug and release.
tasks.matching { it.name == "testChatgptCheckUnitTest" }.configureEach {
    (this as Test).filter {
        excludeTestsMatching("com.thalia.app.voicecheckins.schedule.PlatformWiringTest")
        excludeTestsMatching("com.thalia.app.voicecheckins.schedule.SchedulingEndToEndTest")
    }
}
