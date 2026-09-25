set shell := ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

# Everything this repository can do. `just` on its own lists it.
default:
    @just --list

# The Rust bridge alone — the fast loop, because `cargo test` opens a real
# library and needs no emulator, no Gradle and no JVM.
bridge:
    cargo test --manifest-path rust/Cargo.toml

# What can fail, failing: the bridge's tests, the app's unit tests, then the
# debug APK (which is what links the .so through cargo-ndk).
check:
    cargo test --manifest-path rust/Cargo.toml; if (-not $?) { exit 1 }
    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain; if (-not $?) { exit 1 }

# libquire_bridge.so alone, for the ABI gradle.properties names. Routed through
# Gradle rather than calling cargo-ndk here, so the ABI has one source of truth.
android-lib:
    .\gradlew.bat :app:buildRustBridge --console=plain

# The release APK, signed with the keystore keystore.properties points at.
android-apk:
    .\gradlew.bat :app:assembleRelease --console=plain

# Debug APK, installed over the existing app so its library survives the update.
install:
    .\gradlew.bat :app:assembleDebug --console=plain; if (-not $?) { exit 1 }
    adb install -r app/build/outputs/apk/debug/app-debug.apk

# Bump the patch, build the release APK, and publish it as a GitHub release —
# the whole delivery, because Obtainium watches this repository's releases.
release-publish:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/release-publish.ps1

# Remove every build output, on both sides of the bridge.
clean:
    cargo clean --manifest-path rust/Cargo.toml
    .\gradlew.bat clean --console=plain
