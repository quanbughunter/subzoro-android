import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mỗi lần GitHub dựng là một số bản mới, để cài đè lên bản cũ được.
val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.quanbughunter.subzoro"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.quanbughunter.subzoro"
        minSdk = 29          // Android 10: bắt tiếng app khác (AudioPlaybackCapture)
        targetSdk = 35
        versionCode = buildNo
        versionName = "0.1.$buildNo"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Khoá ký cố định (khoá thử, không bí mật) để bản mới cài đè được lên bản cũ.
    signingConfigs {
        getByName("debug") {
            storeFile = file("subzoro-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // giải nén thư viện .so khi cài (JNA của Vosk cần)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Vosk: nhận dạng giọng nói ngay trên máy, không cần mạng (cùng bộ extension đang dùng để chấm phát âm)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
