plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxyctrl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxyctrl"
        minSdk = 30        // Android11 API30，适配安卓11设备
        targetSdk = 31     // 保持不变，不升级
        versionCode = 1
        versionName = "1.0"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "dummy.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "dummy"
            keyAlias = System.getenv("KEY_ALIAS") ?: "dummy"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "dummy"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
        targetCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
}
