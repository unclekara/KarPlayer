plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.karplayer.srt"
    compileSdk = 36

    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 26
        // -PabiFilter=arm64-v8a builds one architecture, which is the
        // difference between a two-minute experiment and a ten-minute
        // one when libsrt is compiled from source for each.
        ndk {
            val only = project.findProperty("abiFilter") as String?
            abiFilters += if (only != null) listOf(only) else listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden", "-O2")
                // 16 KB page-size alignment for Android 15+. The flag goes to
                // every shared lib we link in this module, including libsrt
                // built via ExternalProject_Add (see CMakeLists.txt).
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DKARPLAYER_BUILD_LIBSRT_FROM_SOURCE=ON",
                    // -PsrtVersion=v1.5.7 to try another libsrt without
                    // editing this file. The pin stays 1.5.4 until a
                    // newer one is measured: 1.5.7 added a control-packet
                    // length check that drops an ACKACK with no payload,
                    // and with it the receiver's RTT, its NAK pacing and
                    // its TSBPD drift correction. Senders built on gosrt
                    // send exactly that ACKACK.
                    "-DSRT_VERSION=" + ((project.findProperty("srtVersion") as String?) ?: "v1.5.4")
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
