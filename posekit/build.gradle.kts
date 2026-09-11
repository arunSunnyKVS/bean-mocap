plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.posekit"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The .task model is already compressed; letting Gradle re-compress it
    // stops MediaPipe from memory-mapping it straight out of the APK.
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // `api`, not `implementation`: consumers bind PoseAnalyzer as an
    // ImageAnalysis.Analyzer, so CameraX types appear in posekit's public API.
    api("androidx.camera:camera-core:1.4.2")
    api("androidx.camera:camera-camera2:1.4.2")
    api("androidx.camera:camera-lifecycle:1.4.2")
    api("androidx.camera:camera-view:1.4.2")

    api("com.google.mediapipe:tasks-vision:0.10.26.1")

    testImplementation("junit:junit:4.13.2")
}
