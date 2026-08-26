import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.github.triplet.play")
    id("com.google.gms.google-services")
}

// Load keystore.properties if present (local dev); CI injects env vars directly.
val keystoreProps = Properties().also { props ->
    val file = rootProject.file("keystore.properties")
    if (file.exists()) props.load(file.inputStream())
}

android {
    namespace = "com.alive.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.wearealive.player"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0.${versionCode}"

        // API base URL — override via local.properties: apiBaseUrl=https://your-server.com
        val localProps = Properties().also { p ->
            val f = rootProject.file("local.properties")
            if (f.exists()) p.load(f.inputStream())
        }
        val apiUrl = System.getenv("API_BASE_URL")
            ?: localProps.getProperty("apiBaseUrl")
            ?: "https://wearealive.in"
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                System.getenv("SIGNING_STORE_FILE")
                    ?: keystoreProps["storeFile"] as String? ?: "release.jks"
            )
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                ?: keystoreProps["storePassword"] as String? ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                ?: keystoreProps["keyAlias"] as String? ?: ""
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                ?: keystoreProps["keyPassword"] as String? ?: ""
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Per-TV-model builds. Same applicationId/signing/server across all of them (so
    // pairing, OTA and the backend behave identically) — flavors exist only to carry
    // device-specific tuning via BuildConfig and to emit separately-named APKs
    // (app-<flavor>-<buildType>.apk) so each panel family gets its own artifact.
    //
    //   DEVICE_PROFILE       — read at runtime for any per-panel branch.
    //   DOUBLE_BUFFER_VIDEO  — prepare the next clip on a 2nd surface for a gapless cut.
    //                          Needs two concurrent AVC decoders; OFF where the panel has
    //                          one (Realtek/SPPL "Kodak"), where a 2nd decoder contends
    //                          with the playing one and makes boundary stalls worse
    //                          (measured on-device 2026-08-11). EXPERIMENTAL — leave off
    //                          until validated on the specific panel.
    flavorDimensions += "device"
    productFlavors {
        create("generic") {
            dimension = "device"
            isDefault = true
            buildConfigField("String", "DEVICE_PROFILE", "\"generic\"")
            buildConfigField("Boolean", "DOUBLE_BUFFER_VIDEO", "false")
        }
        create("kodak") {
            dimension = "device"
            buildConfigField("String", "DEVICE_PROFILE", "\"kodak\"")
            buildConfigField("Boolean", "DOUBLE_BUFFER_VIDEO", "false")
        }
    }

    buildTypes {
        debug {
            // Override via local.properties: demoMode=true  (default: false → real backend)
            val localProps = Properties().also { p ->
                val f = rootProject.file("local.properties")
                if (f.exists()) p.load(f.inputStream())
            }
            val demoMode = System.getenv("DEMO_MODE")
                ?: localProps.getProperty("demoMode")
                ?: "false"
            buildConfigField("Boolean", "DEMO_MODE", demoMode)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("Boolean", "DEMO_MODE", "false")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

play {
    serviceAccountCredentials.set(
        file(System.getenv("PLAY_SERVICE_ACCOUNT_JSON") ?: "play-service-account.json")
    )
    track.set("internal")          // promote to alpha/beta/production manually in Console
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
}

dependencies {
    // Play in-app updates intentionally NOT used — these are unattended kiosks with no
    // one to accept a prompt, and updates arrive OTA via UpdateCheckWorker instead.

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.zxing:core:3.5.2")

    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for local unit tests — the android.jar stub throws on every call.
    testImplementation("org.json:json:20240303")

    // On-device tests (cache/download behaviour needs the real filesystem + Room).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
