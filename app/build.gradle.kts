plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.picosoft.xrayproxydroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.picosoft.xrayproxydroid"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // xray-core (libv2ray.aar) ships native libgojni.so per ABI.
        // arm64-v8a — основная цель (Samsung); armeabi-v7a — старые устройства;
        // x86_64 — временно для эмулятора.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // libv2ray.aar лежит в app/libs; забираем и его нативные .so оттуда.
    sourceSets["main"].jniLibs.srcDirs("libs")
}

dependencies {
    // xray-core wrapper (AndroidLibXrayLite), prebuilt AAR from GitHub releases.
    implementation(files("libs/libv2ray.aar"))

    // Хранение подписок/серверов в JSON (filesDir).
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}