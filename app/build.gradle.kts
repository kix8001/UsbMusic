plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.usbmusic.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.usbmusic.player"
        minSdk = 29
        targetSdk = 34
        versionCode = 27
        versionName = "0.7.3"
    }

    signingConfigs {
        create("fixed") {
            // 個人利用のアプリなので鍵をリポジトリ（非公開）に置き、毎回同じ署名にする
            storeFile = file("usbmusic.jks")
            storePassword = "usbmusic123"
            keyAlias = "usbmusic"
            keyPassword = "usbmusic123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            // ナビで滑らかに動くよう最適化したリリース版を作る（署名はデバッグ鍵で、個人利用向け）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fixed")
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    // AC3 / DTS など Android が標準で再生できない音声を再生するための FFmpeg（Jellyfin がビルドしたもの）
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
