plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedSigningStore = System.getenv("ANDROID_KEYSTORE_PATH")
val sharedSigningPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")

android {
    namespace = "com.wristbrowser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wristbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
    }
    signingConfigs {
        create("velaRelease") {
            if (!sharedSigningStore.isNullOrBlank() && !sharedSigningPassword.isNullOrBlank()) {
                storeFile = file(sharedSigningStore)
                storeType = "PKCS12"
                storePassword = sharedSigningPassword
                keyAlias = "wristbrowser"
                keyPassword = sharedSigningPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!sharedSigningStore.isNullOrBlank() && !sharedSigningPassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("velaRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
