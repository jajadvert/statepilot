plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.execution.app.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.execution.wear"
        minSdk = 30
        targetSdk = 33  // Wear OS 4 emulator image is API 33
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    splits { abi { isEnable = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":wear-protocol"))
    implementation("androidx.wear:wear:1.3.0")

    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(kotlin("test"))
}
