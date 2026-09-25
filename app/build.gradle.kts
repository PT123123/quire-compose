import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Machine-local configuration, both files gitignored:
//   local.properties     — the SDK path (and anything else personal)
//   keystore.properties  — the release signing identity
//
// The keystore lives outside the repository on purpose. It is an identity, not
// a build artifact: Android refuses an update signed with a different key, so a
// key that travels with a clone makes every install's future a clone's problem.
// When the file is absent the release build stays unsigned rather than falling
// back to the debug key — a debug-signed "release" installs fine and then
// breaks in-place updates, which is the worst kind of silent failure.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists()

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
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
}
