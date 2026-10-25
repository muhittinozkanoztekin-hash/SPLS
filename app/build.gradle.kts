plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mhttn.sunplusremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mhttn.sunplusremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"
    }

    // libnative_lib.so is reused as-is from the original Ucast APK (arm64-v8a
    // + armeabi-v7a). Nothing to build here - AGP packages
    // src/main/jniLibs/<abi>/*.so automatically.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
