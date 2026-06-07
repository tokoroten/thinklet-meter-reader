plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.meterreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.meterreader"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }   // THINKLET=arm64。CameraXのネイティブを絞りAPK肥大を抑制
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // CameraX（連続プレビュー＝ImageAnalysis のみ使用。Preview/ImageCapture use case は使わない）
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")

    // APIキーの暗号化保存（Android Keystore 由来のマスターキーで EncryptedSharedPreferences）
    implementation("androidx.security:security-crypto:1.0.0")

    // 保存JPEGへの GPS EXIF 書き込み（framework版に setLatLong が無いため公式の androidx 版を使用）
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // バーコード/QR 検出（端末内バンドル版＝GMS非依存）。顧客ID・メーターIDの読み取りに使用
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // mDNS（純Java・GMS非依存）。m####.local でブラウザ/adb から名前アクセス
    implementation("org.jmdns:jmdns:3.5.8")

    // ネットワークは HttpURLConnection、JSON は org.json（いずれも標準同梱＝追加依存なし）
    // ML Kit / OpenCV / barcode は不使用（メータ読取はクラウドVLMが担当）
}
