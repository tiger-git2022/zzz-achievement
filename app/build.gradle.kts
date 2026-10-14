plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// CI passes BUILD_NUMBER (GITHUB_RUN_NUMBER); local builds fall back to 0.
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
val baseVersionName = "1.1"

// Release signing comes from the environment (see .github/workflows/release.yml).
// Without it, release builds are signed with the debug key so local builds keep working.
val releaseKeystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)
val hasReleaseSigning = releaseKeystoreFile?.isFile == true &&
    listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !System.getenv(it).isNullOrEmpty() }

android {
    namespace = "com.local.stardbzzz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.local.stardbzzz"
        minSdk = 26
        targetSdk = 35
        // Offset keeps CI builds above the hand-set versionCode 2 used before CI existed.
        versionCode = if (buildNumber > 0) 100 + buildNumber else 2
        versionName = if (buildNumber > 0) "$baseVersionName.$buildNumber" else baseVersionName
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())

        // The OCR runtime (ONNX Runtime) is ~20 MB of native code per ABI; 64-bit ARM covers current phones.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off; turning it on needs keep rules verified at runtime for the
            // WebView @JavascriptInterface bridge, Retrofit interfaces and Room.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // On-device OCR for the in-game achievement scan: PaddleOCR models (assets/ppocr) on ONNX Runtime.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    testImplementation("junit:junit:4.13.2")
    // Same API with desktop native libraries, so unit tests can run the bundled models.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    // android.jar only has stubs of org.json; the stardb data parser tests need the real thing.
    testImplementation("org.json:json:20240303")
}
