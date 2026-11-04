plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.audiomix"
    compileSdk = 35

    // Zählt bei jedem GitHub-Build automatisch hoch (nötig für Updates)
    val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

    defaultConfig {
        applicationId = "com.example.audiomix"
        minSdk = 29
        targetSdk = 35
        versionCode = 100 + runNumber
        versionName = "0.5.$runNumber"
    }

    signingConfigs {
        create("audiomix") {
            storeFile = file("signing/audiomix.jks")
            storePassword = "audiomix123"
            keyAlias = "audiomix"
            keyPassword = "audiomix123"
        }
    }

    buildTypes {
        getByName("debug") {
            if (file("signing/audiomix.jks").exists()) {
                signingConfig = signingConfigs.getByName("audiomix")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
