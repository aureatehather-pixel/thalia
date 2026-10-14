pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "thalia-bot"

// :core is the portable domain and provider contract, with no Android dependency.
// :app is the Thalia Android application.
// :voice-gemini is the Gemini Live provider behind :core's VoiceSessionClient. Plain Kotlin on the JVM, and
// only the M3A spike uses it, from debug builds. See docs/adr/0008-m3a-manual-gemini-live-spike.md.
include(":core")
include(":app")
include(":voice-gemini")
// :chatgpt-plan is the isolated ChatGPT-plan and spoken-reply feasibility proof. Only the chatgptCheck build type uses it.
include(":chatgpt-plan")
// :chatgpt-provider is the production-side ChatGPT-plan provider (no proof code, no sign-in flow); :chatgpt-plan builds on it.
include(":chatgpt-provider")
// :openai-provider is the everyday V1 reasoning and spoken-reply side: a ConversationProvider over OpenAI's Responses API with
// a normal API key (not ChatGPT sign-in), and a Google Cloud Text-to-Speech client for the Chirp 3 HD Aoede voice. Plain Kotlin
// on the JVM, so every check runs against a local fake server. Only debug and the live check build depend on it.
include(":openai-provider")
// :intent-cases is the provider-neutral fixture of eight intent cases, scored on meaning through the real parser and validator.
include(":intent-cases")
