import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from a gitignored keystore.properties (see keystore.properties.example).
// Without it the release build is left unsigned rather than signed with the debug key: F-Droid
// rebuilds the app and compares its build against the APK published on GitHub, which only works
// when its own build carries no signature to strip.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { keystoreProperties.getProperty(it) != null }

android {
    namespace = "app.devswitch"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.devswitch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            // AGP would otherwise write the git revision into META-INF/version-control-info.textproto,
            // so an APK built from any later commit could never match the published one in F-Droid's
            // reproducibility check. Nothing reads that file.
            vcsInfo {
                include = false
            }
        }
    }

    // Google Play's encrypted dependency list is an opaque blob F-Droid cannot inspect, and nothing
    // here is uploaded to Play, so leave it out of every artifact.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Keep every native library exactly as its Maven artifact ships it. AGP would otherwise strip
    // them only when it happens to find an NDK, so the APK would differ between machines and fail
    // F-Droid's reproducible-build comparison.
    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The Shizuku user service is defined with AIDL (src/main/aidl).
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // QR image for the in-app wireless pairing flow, and decoding a scanned QR.
    implementation("com.google.zxing:core:3.5.4")

    // Camera preview and frame analysis to scan Android Studio's pairing QR.
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    // Lets the app grant itself WRITE_SECURE_SETTINGS on-device (no computer) when Shizuku runs.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Reflect on the framework's hidden IAdbManager to drive wireless pairing through Shizuku.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation("junit:junit:4.13.2")
}
