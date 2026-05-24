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
    namespace = "com.partner.alive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.partner.alive"
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
    implementation("com.google.android.play:app-update-ktx:2.1.0")

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
}
