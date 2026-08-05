import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials, read from keystore.properties (gitignored) so
// secrets never live in the repo. When the file is absent (fresh clone, CI,
// another machine), the release buildType simply has no signingConfig and
// produces an unsigned artifact; debug builds are unaffected. This keeps the
// project buildable everywhere while only THIS machine signs releases.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.muddassir.clearview"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.muddassir.clearview"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // TFLite ships native libs for every ABI (~8MB when all four are
        // bundled). Restrict to the ABIs this app targets; add x86_64 back if
        // you run the app on an x86_64 emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // Keep the .tflite model uncompressed in the APK so Interpreter can
    // memory-map it directly from assets (mmap fails on compressed entries).
    androidResources {
        noCompress += "tflite"
    }

}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
   implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // On-device NSFW thumbnail classification (feed image blocking). The model
    // (app/src/main/assets/nsfw_detector.tflite) is the Yahoo open_nsfw model
    // (~5.7MB, Apache-2.0; mirrored from devzwy/TensorflowLite-NSFW-Android).
    // Verified shapes: input [1,224,224,3] FLOAT32, output [1,2] = [safe,nsfw]
    // softmax. The analyzer's float path preprocesses it OpenNSFW-style (BGR +
    // mean subtraction) — see ThumbnailSafetyAnalyzer. Replacing the asset with
    // a different model family (nsfwjs RGB[0,1], quantized uint8, 5-class...)
    // requires matching preprocessing; the MODEL_LOADED logcat line shows the
    // detected shapes. NOTE: assets/*.tflite is NOT gitignored, so the model
    // ships in the APK.
    //
    // LiteRT (com.google.ai.edge.litert) is Google's successor to TFLite:
    // 16 KB page-size aligned native libs (tensorflow-lite <= 2.15 fails to
    // load on Android 15+ / 16 KB-page devices — the 2.6.0 build hit exactly
    // that), and a modern runtime that loads models exported by recent TF.
    // It keeps the org.tensorflow.lite.* Java packages, so the analyzer's
    // imports stay unchanged. litert + litert-api both declare namespace
    // "com.google.ai.edge.litert", hence android.uniquePackageNames=false
    // in gradle.properties.
    implementation(libs.litert)

    testImplementation(libs.junit)
    // org.json is stubbed in the Android SDK; provide the real JVM impl for unit tests
    testImplementation(libs.org.json)

    // The Compose BOM must ALSO be on the androidTest + debug classpaths. The
    // versionless ui-test-junit4 / ui-test-manifest artifacts are only resolved
    // through BOM constraints; without the platform here, lint (which builds an
    // androidTest model) fails with "Could not find androidx.compose.ui:ui-test-junit4:".
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
