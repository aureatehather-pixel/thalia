# Third-party notices

Thalia's own source is the author's work (license pending, see README). It uses the components below. Versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml). License names are the upstream projects' published licenses; confirm against each artifact before redistributing a binary.

## Bundled in the source tree

### Atkinson Hyperlegible Next (font)

- File: `app/src/main/res/font/atkinson_hyperlegible_next.ttf`
- Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors (https://github.com/googlefonts/atkinson-hyperlegible-next)
- License: SIL Open Font License 1.1. The full text is in [docs/third-party/atkinson-hyperlegible-next-OFL.txt](docs/third-party/atkinson-hyperlegible-next-OFL.txt) and must accompany any redistribution of the font, including an APK that contains it.

### Gradle wrapper

- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`: Apache License 2.0, Gradle Inc.

## Build-time and runtime dependencies (fetched by Gradle, not vendored)

| Component | License |
|---|---|
| Kotlin, kotlinx.coroutines, kotlinx.serialization (JSON) | Apache 2.0 |
| AndroidX (core-ktx, activity-compose, lifecycle, Room, test) and Jetpack Compose (BOM, UI, Material 3) | Apache 2.0 |
| OkHttp, MockWebServer | Apache 2.0 |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) | BSD-style license from Zetetic LLC, including the SQLCipher community edition notice |
| Robolectric | MIT |
| JUnit 4 | EPL 1.0 |
| JUnit 5 (Jupiter, Vintage engine) | EPL 2.0 |
| ktlint Gradle plugin | MIT |

## Services

The optional provider modules call external services (OpenAI, Google Cloud Text-to-Speech, Gemini, ChatGPT sign-in) only with the user's own credentials. Those services' terms apply to that use; no keys, tokens or service output are included in this repository.
