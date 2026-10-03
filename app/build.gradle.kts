plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pteronpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pteronpdf"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Same as release (minified + shrunk) but signed with the debug key, so it installs
        // without a production keystore. Use its size as the real APK size.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // One small APK per CPU type instead of one big APK holding all of them. A phone only needs its own,
    // so every device is supported and nobody downloads native code they can't run.
    //   arm64-v8a   = nearly all phones since ~2016      armeabi-v7a = older / low-end 32-bit phones
    //   x86_64, x86 = Chromebooks, emulators, a few tablets
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    // UI is English only: drop the translated strings that Material/AndroidX bring along.
    androidResources { localeFilters += listOf("en") }
    // Don't embed the dependency list (only useful for Play's own tooling).
    dependenciesInfo { includeInApk = false; includeInBundle = false }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/META-INF/*.kotlin_module", "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "/kotlin/**")
        jniLibs.useLegacyPackaging = false   // keep .so uncompressed & mmap'd: smaller install, faster start
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Same engine family as PyMuPDF on desktop -> identical rendering,
    // search, annotations and save behaviour. (AGPL — see README.)
    implementation("com.artifex.mupdf:fitz:1.24.+")
}
