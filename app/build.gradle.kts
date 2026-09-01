plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxyctrl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxyctrl"
        minSdk = 30        // Android11 API30
        targetSdk = 31     // 坚持不变
        versionCode = 1
        versionName = "1.0"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    // 删除 signingConfigs 块！release不绑定签名，输出未签名包
    buildTypes {
        release {
            isMinifyEnabled = false
            // signingConfig = signingConfigs.getByName("release") // 注释掉！
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
