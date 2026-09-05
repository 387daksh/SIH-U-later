plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.sih.offlinespeech"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.sih.offlinespeech"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4-sutra"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
}
