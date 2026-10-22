import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.callnotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.callnotes"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "0.7.0"

        // LiteRT-LM ships native libraries; phones are arm64. Keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // whisper.cpp unoptimised is many times slower: always build it as Release,
                // also inside the debug APK we distribute.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    // whisper.cpp (third_party/whisper.cpp, git submodule) + our JNI bridge.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // One fixed debug key (committed on purpose) so every build — from Android Studio
    // or from GitHub Actions — installs as an update over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Sideloaded build: the battery-exemption request is intentional (Play policy n/a).
        disable += "BatteryLife"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // On-device Gemma 4 inference (text + audio).
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    // Real org.json for unit tests (android.jar's copy is a stub there).
    testImplementation("org.json:json:20240303")
}
