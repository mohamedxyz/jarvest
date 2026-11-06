import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val coreDir = rootProject.projectDir.resolve("../core").canonicalFile
val generatedKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val rustJniLibsDir = layout.buildDirectory.dir("generated/rust/jniLibs")

// Signing details stay out of the project files. See scripts/create-keystore.sh.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

// "-Parm64Only=true" builds only for real phones. That halves the Rust build time.
val appAbis: List<String> =
    if (project.findProperty("arm64Only") == "true") listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")

android {
    namespace = "dev.goaltracker.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.goaltracker.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.11.0"

        ndk {
            // Only the CPU types the Rust core is built for. Real phones are arm64; x86_64 is for
            // emulators. Release builds on CI pass -Parm64Only=true to build the Rust core once, not twice.
            abiFilters += appAbis
        }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/libjnidispatch.so"
    }

    sourceSets.getByName("main") {
        kotlin.directories.add(generatedKotlinDir.get().asFile.path)
        jniLibs.directories.add(rustJniLibsDir.get().asFile.path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Rust tools live in ~/.cargo/bin. Putting it first makes the pinned toolchain win.
val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
val pathWithCargo = "$cargoBin${File.pathSeparator}${System.getenv("PATH")}"
val rustSources = fileTree(coreDir) {
    include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml")
}

val buildRustLibs = tasks.register<Exec>("buildRustLibs") {
    description = "Compiles the Rust core for each Android CPU type."
    inputs.files(rustSources)
    outputs.dir(rustJniLibsDir)
    workingDir(coreDir)
    environment("PATH", pathWithCargo)
    environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.ndkDirectory.get().asFile.path)
    commandLine(
        "cargo", "ndk", *appAbis.flatMap { listOf("-t", it) }.toTypedArray(), "-P", "26",
        "-o", rustJniLibsDir.get().asFile.path,
        "build", "--release", "--lib",
    )
}

val generateKotlinBindings = tasks.register<Exec>("generateKotlinBindings") {
    description = "Generates Kotlin code for calling the Rust core."
    inputs.files(rustSources)
    outputs.dir(generatedKotlinDir)
    workingDir(coreDir)
    environment("PATH", pathWithCargo)
    // Bindings are read from an unstripped library built for this computer.
    commandLine(
        "sh", "-c",
        "cargo build --lib && cargo run --quiet --features bindgen --bin uniffi-bindgen -- " +
            "generate target/debug/libgoal_core.so --language kotlin --no-format " +
            "--out-dir '${generatedKotlinDir.get().asFile.path}'",
    )
}

tasks.named("preBuild") {
    dependsOn(buildRustLibs, generateKotlinBindings)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    // Glance's own WorkManager version is too old for current R8 and crashes release builds.
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // For the theme contrast test (src/test). Not shipped in the app.
    testImplementation("junit:junit:4.13.2")
}
