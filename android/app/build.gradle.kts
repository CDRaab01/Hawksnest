import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

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
        // ONE committed key signs everything. It plays two roles:
        //
        //  1. SIDELOAD identity — debug + the release.apk cut for sideloading all share this
        //     certificate, so a new APK installs *over the top* of the previous one (an in-place
        //     update) instead of failing with "App not installed as package conflicts with an
        //     existing package" (INSTALL_FAILED_UPDATE_INCOMPATIBLE). The saved HA token survives.
        //
        //  2. Play UPLOAD key — this same key signs the AAB we upload to Play. With Play App
        //     Signing, Google holds the real *app signing key* and re-signs every install, so the
        //     upload key never reaches a device. Reusing this committed key as the upload key is a
        //     deliberate choice for a personal internal-test app: zero secrets, CI publishes
        //     unattended. KEEP this key — once Play records it as the upload certificate, losing it
        //     means registering a replacement upload key with Google support.
        //
        // It secures nothing on its own (the password is intentionally not secret); its only jobs
        // are sideload install-continuity and being a stable Play upload identity.
        create("stable") {
            storeFile = file("hawksnest-debug.keystore")
            storePassword = "hawksnest"
            keyAlias = "hawksnest"
            keyPassword = "hawksnest"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
            // Distinct package so a sideloaded debug build can coexist on the same device as the
            // Play-installed release (com.hawksnest). Without this, the two carry different signing
            // certificates under the same applicationId and Android rejects the second as a package
            // conflict. (The instrumented-test runner targets com.hawksnest.debug.test — see
            // scripts/android-emulator-test.sh.)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Same committed key as debug — see signingConfigs above. Keeping one identity across
            // every variant is what lets sideloaded installs update in place.
            signingConfig = signingConfigs.getByName("stable")
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

// ── Google Play publishing (Gradle Play Publisher) ──────────────────────────────────────────────
// Publishes the release AAB straight to the Play **internal testing** track. Authentication is a
// Google Play service-account JSON: CI writes the PLAY_SERVICE_ACCOUNT_JSON secret to the path
// below before running `:app:publishReleaseBundle`; locally, drop the same file at
// android/play-service-account.json (git-ignored) to publish from a workstation.
//
// The credentials file is resolved lazily — applying this plugin without it present does not break
// other Gradle tasks (build, test, assembleDebug); only the publish* tasks require it at run time.
// NOTE: the Play app must already exist and have had one AAB uploaded manually (to enroll in Play
// App Signing and record the upload cert) before automated publishing works — GPP cannot create the
// app. See android/PLAYSTORE.md.
play {
    serviceAccountCredentials.set(rootProject.file("play-service-account.json"))
    track.set("internal")
    defaultToAppBundles.set(true)
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

    // Coil — camera snapshot images (live MJPEG is hand-rolled on OkHttp)
    implementation(libs.coil.compose)

    // Media3 / ExoPlayer — HLS live + recorded VOD playback and the demo clip (the Ring-style
    // camera player). HLS module pulls in the playlist parser; MJPEG stays on the OkHttp decoder.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    // WebRTC (go2rtc/ring-mqtt low-latency live) — a maintained packaging of Google's libwebrtc that
    // exposes the standard `org.webrtc.*` API. Negotiated over HA's `camera/webrtc/offer`.
    implementation(libs.stream.webrtc.android)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Compose UI tests (instrumented) — drive the real app against the mock-ha server. The
    // ui-test-manifest (host activity for the Compose rule) ships in the debug variant.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

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
