plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:intent-cases` holds the eight intent cases as a provider-neutral fixture: the inputs, the fixed clock and zone, the synthetic
// planning day, and a scorer that reads meaning from what the real parser and validator made of a reply. It names no provider.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
