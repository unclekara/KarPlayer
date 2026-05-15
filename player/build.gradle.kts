plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.karplayer.player"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":srt"))

    api(libs.media3.exoplayer)
    api(libs.media3.datasource)
    api(libs.media3.extractor)
    api(libs.media3.common)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
