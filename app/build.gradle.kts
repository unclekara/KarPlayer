plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.karplayer"
    compileSdk = 34
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.karplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // For now use the standard debug keystore so adb install works out of the
    // box. Replace with a real keystore before any Play Store / public release.
    signingConfigs {
        getByName("debug") {
            // Default — ~/.android/debug.keystore. AGP creates it on first build.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
