plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pocketgps.logger"
    compileSdk = 34

    // Do not embed the Play-encrypted dependency blob; nothing here uses it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.pocketgps.logger"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0"

        // The UI is English-only. Strips every other translation AndroidX/Compose/Play
        // libraries bundle (locale tables for 80+ languages) from the APK.
        resourceConfigurations += "en"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Sign with the debug keystore so `assembleRelease` produces an APK that
            // installs directly, with no manual signing step. Swap to a real release
            // keystore before publishing to Play (see README §6).
            signingConfig = signingConfigs.getByName("debug")

            // The intended phone is ARM64. Restricting the *release* build to one ABI keeps
            // the native Vosk + JNA payload small. Debug builds keep every ABI packaged by the
            // AARs so they also install on x86_64 emulators. Add "armeabi-v7a" here for 32-bit
            // devices (see README).
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        // Store native libs DEFLATE-compressed inside the APK. libvosk.so (~8.9 MB raw)
        // compresses to roughly half, halving download size for sideloads. Cost: libs are
        // extracted at install time (one-time) and first load is marginally slower.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Coroutine debug-probe metadata and library version text files are
            // developer-only; none of it is needed at runtime.
            excludes += "DebugProbesKt.bin"
            excludes += "/META-INF/*.version"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // Export Room schemas so future migrations can be written against a known baseline.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Kotlin & coroutines
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    // NOTE: appcompat removed — MainActivity is a ComponentActivity and the XML theme
    // extends the platform Theme.Material, so the ~1 MB library was dead weight.

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // NOTE: material-icons-extended removed — the 15 icons used are defined by hand in
    // ui/icons/AppIcons.kt with the same path data. Keeps only what is on screen.
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Offline keyword spotting was removed in v2 — the app is tap-first, no speech model.

    // Fused Location Provider (primary); LocationManager used as fallback when Play Services
    // is absent. Only the location module is pulled in, not the full play-services bundle.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}