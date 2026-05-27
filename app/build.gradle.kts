plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.telegramplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.telegramplayer"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        // TMDB API key for movie poster fetching
        buildConfigField("String", "TMDB_API_KEY", "\"7c91e5ebefe05ac02cfa97ccc8edc7f6\"")

        // Filter for Fire TV architectures (arm64-v8a primary, armeabi-v7a fallback)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "password"
            keyAlias = "release"
            keyPassword = "password"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }

    // Configure jniLibs location
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

tasks.withType<JavaCompile> {
    enabled = false
}

dependencies {
    // Leanback UI for Fire TV
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // NanoHTTPD for local streaming proxy
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Coroutines for async TDLib operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // QR Code generation for auth
    implementation("com.google.zxing:core:3.5.2")

    // Glide for image loading (channel thumbnails)
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
