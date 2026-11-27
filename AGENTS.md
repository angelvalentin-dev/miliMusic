# Working with miliMusic

**miliMusic** is a Windows desktop YouTube Music client written in Kotlin with Compose Multiplatform.

## Repository structure

- `:desktop`: Windows Compose Multiplatform application, audio playback (libVLC), SQLite storage, preferences, integrations, and native Windows C++ helpers (`desktop/native/`).
- `:innertube`: Kotlin JVM YouTube Music InnerTube API client.

## Build and test commands

Run unit tests for both modules from the repository root:

```bash
./gradlew :innertube:test :desktop:test
```

Package Windows installers and distributables (on Windows x64):

```bash
./gradlew :desktop:packageExe
./gradlew :desktop:packageMsi
./gradlew :desktop:createDistributable
```

## Guidelines

- Keep commit messages clear using conventional format (`type(scope): short description`).
- Preserve screen reader accessibility and keyboard navigation across desktop UI components.
- Do not commit generated native runtime artifacts under `desktop/resources/windows/` or `desktop/native/packages/`.
