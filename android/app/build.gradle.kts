import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// Is the Sift design-slop auditor available as a composite build (sibling checkout, or -PsiftDir in
// CI)? Mirrors the guard in settings.gradle.kts. When false (a standalone Hawksnest checkout), the
// test-only Sift audit source set + deps below are skipped so `:app:testDebugUnitTest` stays green.
val siftDir: String = (project.findProperty("siftDir") as String?) ?: "../../Sift"
val siftAvailable: Boolean =
    rootProject.projectDir.resolve(siftDir).resolve("settings.gradle.kts").exists()

android {
    namespace = "com.hawksnest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hawksnest"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE (the run number) so each signed release installs cleanly over the
        // previous one; defaults to 1 for local/debug builds.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional default Home Assistant base URL (the Hawksnest proxy host, reachable over
        // Tailscale, e.g. http://hawksnest.tailnet:8080). Empty by default — the user enters it in
        // Settings. Set `ha.url=...` in local.properties to prefill debug builds.
        buildConfigField(
            "String", "HA_DEFAULT_URL",
            "\"${localProperties.getProperty("ha.url", "")}\""
        )
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // The Sift design-slop suite (Robolectric render of our Compose UI) lives in its own source dir
    // so it is compiled into the unit-test source set only when the Sift composite build is wired.
    if (siftAvailable) {
        sourceSets.getByName("test").java.srcDirs("src/siftAudit/kotlin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // OkHttp (WebSocket + REST) + Serialization. No Retrofit yet — the HA WebSocket client
    // is hand-rolled on OkHttp; REST helpers are added in Phase 2.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // DataStore (HA URL + long-lived token)
    implementation(libs.datastore.preferences)

    // Biometric gate (Class-3 strong biometric on unlock/disarm)
    implementation(libs.androidx.biometric)

    // Coil — camera snapshot images (live MJPEG is hand-rolled on OkHttp)
    implementation(libs.coil.compose)

    // Media3 / ExoPlayer — HLS live + recorded VOD playback and the demo clip (the Ring-style
    // camera player). HLS module pulls in the playlist parser; MJPEG stays on the OkHttp decoder.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)

    // ── Sift design-slop audit (test-only) ──────────────────────────────────────────────────────
    // Wired only when the Sift composite build is present. `sift-compose` exposes Robolectric +
    // Compose UI test as `api`, so they arrive transitively; the BOM platform + ui-test-manifest
    // (host activity for the Robolectric render) are added explicitly. See the `/sift` skill and
    // app/src/siftAudit/.
    if (siftAvailable) {
        testImplementation(platform(libs.androidx.compose.bom))
        testImplementation("style.sift:sift-compose:0.1.0")
        testImplementation(libs.androidx.compose.ui.test.junit4)
        testImplementation(libs.robolectric)
        debugImplementation(platform(libs.androidx.compose.bom))
        debugImplementation(libs.androidx.compose.ui.test.manifest)
    }
}
