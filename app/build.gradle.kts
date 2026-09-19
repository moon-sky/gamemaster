import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.gamemaster.agent"
    compileSdk = 31
    buildToolsVersion = "31.0.0"

    defaultConfig {
        applicationId = "com.gamemaster.agent"
        minSdk = 29
        targetSdk = 31
        versionCode = 1
        // 版本号取构建时刻：月日时分，如 09111920
        versionName = SimpleDateFormat("MMddHHmm").format(Date())

        // Chaquopy Python 运行时与 Shizuku 后端仅需要 arm64 与 x86_64
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        pip {
            // web_search / web_read 工具依赖（Chaquopy 16 + AGP 7.1 实际跑 Python 3.9.0，
            // 锁定兼容版本：requests 2.32.x + urllib3 2.5.x + soupsieve 2.5.x 仍支持 3.9，
            // 新版已要求 3.10+）
            install("requests==2.32.3")
            install("urllib3<2.6")
            install("idna<3.10")
            install("soupsieve<2.6")
            install("beautifulsoup4<4.13")
            install("charset-normalizer<4")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("com.google.android.material:material:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // Shizuku 后端：让非 Root 设备也能拿到 shell 权限执行截屏/手势/pm install
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
