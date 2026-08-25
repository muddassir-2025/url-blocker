import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
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
        versionCode = 22
        versionName = "10.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            // Install side-by-side with the Play Store release. The debug APK is
            // signed with the debug keystore, so Android refuses to install it
            // over the release app (same applicationId, different signature). A
            // ".debug" suffix gives the debug build its own applicationId
            // (com.muddassir.clearview.debug) — a separate app that coexists
            // with the Play Store one, each with its own data.
            applicationIdSuffix = ".debug"
        }
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
        // java.time.chrono.HijrahDate (Umm al-Qura Islamic calendar) needs core
        // library desugaring — java.time only arrived natively in API 26 and
        // minSdk is 24.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // BuildConfig.APPLICATION_ID — needed by the instrumentation test to
        // assert the variant's real applicationId (the debug build carries a
        // ".debug" suffix).
        buildConfig = true
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
    // On-device YouTube audio extraction (NewPipeExtractor, GPL-3.0-or-later):
    // resolves the direct audio-stream URL from the phone's own IP so downloads
    // never depend on a server. Audio-only streams only — no FFmpeg needed.
    implementation(libs.newpipe.extractor)
    // MediaSessionCompat + MediaStyle notification for the offline-audio
    // foreground service: background playback with lock-screen / notification
    // media controls.
    implementation(libs.androidx.media)

    testImplementation(libs.junit)
    // org.json is stubbed in the Android SDK; provide the real JVM impl for unit tests
    testImplementation(libs.org.json)

    // Core library desugaring (compile-time backport of java.time for API 24/25).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

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

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-analytics")
}
