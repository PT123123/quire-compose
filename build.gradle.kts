// The plugin set, and nothing else: versions live here so a module never
// carries one of its own.
//
// These are the exact versions the other Android project on this machine builds
// with (AGP 8.5.2 / Kotlin 1.9.22 / Compose BOM 2024.06.00), for a boring
// reason worth writing down: they are known to work against the SDK and the
// Gradle 8.9 distribution already installed here, and a shell that cannot build
// is not a shell.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
