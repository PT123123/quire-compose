import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// ---------------------------------------------------------------------------
// The signing identity: ONE keystore, and it signs both build types.
//
// It is named `debug.keystore` and it is not a throwaway. It is the identity
// this app ships under — the name is Android tooling's default for a locally
// generated key, which is what it was before it was promoted to permanent — and
// it is used for the *release*, deliberately. It lives in the user's own
// directory, outside every repository, because a signing key is an identity and
// not a build artifact: Android refuses to install an update signed by a
// different key, so a key that travels with a clone makes every install's future
// a clone's problem.
//
// Debug is signed with it too, on purpose. AGP's default is to sign debug builds
// with its own generated `~/.android/debug.keystore`; when that file happens to
// hold the same key everything looks fine, and the day it is regenerated every
// `just install` puts an app on the device that the next released APK cannot
// update over — the only fix being an uninstall. One identity, both build types,
// no such day.
//
// The store and key passwords are the Android tooling's documented default for
// this key and are public knowledge. The file is the part worth keeping, and it
// is not in here. `keystore.properties` (gitignored) overrides any of the values
// below when it exists.
// ---------------------------------------------------------------------------
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, fallback: String): String =
    signingProps.getProperty(key) ?: fallback

val keystoreFile = file(signingValue("storeFile", "C:/Users/ted/keystores/debug.keystore"))
val keystoreAlias = signingValue("keyAlias", "androiddebugkey")
val keystoreStorePassword = signingValue("storePassword", "android")
val keystoreKeyPassword = signingValue("keyPassword", "android")
val hasSigningIdentity = keystoreFile.exists()

if (!hasSigningIdentity) {
    logger.warn(
        "no signing keystore at ${keystoreFile.absolutePath}: debug builds fall back to AGP's " +
            "generated key and release builds stay UNSIGNED (unpublishable). Restore the keystore, " +
            "or point keystore.properties at one."
    )
}

// One version, in gradle.properties, turned into the two numbers Android wants.
// versionCode has to be monotonic for an update to install, which is why it is
// derived rather than typed: two places to write a version is one place to
// forget.
val quireVersion = providers.gradleProperty("quire.version").get()
val versionParts = quireVersion.split(".").map { it.toIntOrNull() ?: 0 }
require(versionParts.size == 3) { "quire.version must be MAJOR.MINOR.PATCH, got '$quireVersion'" }

android {
    namespace = "dev.quire.compose"
    compileSdk = 34
    ndkVersion = "30.0.15729638"

    defaultConfig {
        // Deliberately not the Rust shell's `dev.quire.android`: both apps are
        // meant to be installable side by side while this one grows up, and two
        // applicationIds that collide would make installing one an update of
        // the other.
        applicationId = "dev.quire.compose"
        minSdk = 24
        targetSdk = 34
        versionCode = versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]
        versionName = quireVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += listOf(providers.gradleProperty("quire.abi").get())
        }
    }

    signingConfigs {
        // Named for what it is — the app's identity — rather than for the build
        // type it is attached to, because it is attached to both.
        if (hasSigningIdentity) {
            create("quire") {
                storeFile = keystoreFile
                storePassword = keystoreStorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
                // v1 (JAR signing) is not needed by minSdk 24 and costs APK
                // size; v2/v3 are what an install on any modern Android checks.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Not AGP's generated key: this app's, so `just install` and the
            // published APK replace each other instead of colliding.
            if (hasSigningIdentity) signingConfig = signingConfigs.getByName("quire")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Unsigned when the identity is missing — an unsigned release cannot
            // be installed at all, which is a louder and more honest failure than
            // one signed by a key that would break the next update.
            if (hasSigningIdentity) signingConfig = signingConfigs.getByName("quire")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Compose compiler matching Kotlin 1.9.22.
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // libquire_bridge.so is built by cargo (see the task below) into a directory
    // that is handed to AGP here. The path is the *parent* of the ABI folders —
    // srcDir must contain `arm64-v8a/`, which is the shape cargo-ndk -o writes.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("rust-jniLibs"))
        }
    }
}

// ---------------------------------------------------------------------------
// The bridge: cargo-ndk builds the Rust crate next to this module for the one
// shipped ABI, and AGP packages the .so like any other prebuilt library.
//
// cargo-ndk rather than a bare `cargo build` with a LINKER variable: quire-core
// links a *bundled* SQLite, so the build also compiles C — and the C compiler
// for an Android target is exactly what cargo-ndk points at the NDK's clang
// wrappers. Without Rust the task disables itself and the app degrades to "the
// library is missing" instead of failing the build, which is what a clean clone
// on a machine without a toolchain should see.
// ---------------------------------------------------------------------------
val quireAbi = providers.gradleProperty("quire.abi").get()
val rustDir = rootProject.file("rust")
val rustJniLibs = layout.buildDirectory.dir("rust-jniLibs")

val cargoExe: String? = run {
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
    val dirs = buildList {
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { add(File(it)) }
        (System.getenv("USERPROFILE") ?: System.getProperty("user.home"))
            ?.let { add(File(it, ".cargo/bin")) }
    }
    dirs.firstOrNull { File(it, exe).isFile }?.let { File(it, exe).absolutePath }
}
if (cargoExe == null) {
    logger.warn(
        "cargo was not found — libquire_bridge.so will not be built. " +
            "Install Rust and `cargo install cargo-ndk`, then rebuild (`just android-apk`)."
    )
}

val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
val ndkHome = sdkDir?.let { file(File(File(it, "ndk"), android.ndkVersion)) }

val buildRustBridge by tasks.registering(Exec::class) {
    enabled = cargoExe != null && ndkHome != null
    description = "Builds libquire_bridge.so for $quireAbi with cargo-ndk."
    workingDir = rustDir
    commandLine(
        cargoExe, "ndk",
        "-t", quireAbi,
        "-o", rustJniLibs.get().asFile.absolutePath,
        "build", "--release"
    )
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome.absolutePath)
    }
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.dir(rustJniLibs)
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }
    .configureEach {
        if (cargoExe != null && ndkHome != null) dependsOn(buildRustBridge)
    }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    val composeBom = "2024.06.00"
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // No material-icons-extended: the chrome's icons are drawn here
    // (ui/Icons.kt), which is also what the Slint shell does — one fewer
    // multi-megabyte dependency in a UI whose SPEC ranks low RAM above
    // maintainability and feature count.
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // The real `org.json`, for the tests only: `android.jar`'s copy is a stub
    // that throws "not mocked" under a JVM test, and the bridge's whole contract
    // is one JSON string — `SyncModelTest` is what pins it. Nothing at runtime
    // changes; the framework's own `org.json` is what the app uses on a device.
    testImplementation("org.json:json:20240303")
}
