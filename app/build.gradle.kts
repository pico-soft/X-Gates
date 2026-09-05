import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Пароли/путь релизного ключа НЕ в репозитории (публичный): читаем из local.properties (игнорируется)
// с фолбэком на переменные окружения (для CI). Хардкода паролей в build.gradle нет.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun releaseProp(name: String): String? = localProps.getProperty(name) ?: System.getenv(name)
val releaseStorePath: String? = releaseProp("RELEASE_STORE_FILE")
val hasReleaseKey: Boolean = releaseStorePath != null && file(releaseStorePath).exists()

android {
    namespace = "com.picosoft.xrayproxydroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.picosoft.xrayproxydroid"
        minSdk = 24
        targetSdk = 37
        versionCode = 27
        // Бампим по +0.01 до принципиальных изменений (напр. sing-box → 2.0). versionCode ++ на релиз.
        versionName = "0.36 beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // xray-core (libv2ray.aar) ships native libgojni.so per ABI.
        // arm64-v8a — основная цель (Samsung); armeabi-v7a — старые устройства;
        // x86_64 — временно для эмулятора.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseProp("RELEASE_STORE_PASSWORD")
                keyAlias = releaseProp("RELEASE_KEY_ALIAS")
                keyPassword = releaseProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8/сжатие ВЫКЛ намеренно (первая релизная сборка): gomobile-AAR (libv2ray) и
            // kotlinx.serialization ломаются без выверенных keep-правил, причём МОЛЧА в рантайме.
            // Включим отдельным заходом с проверкой на устройстве.
            optimization {
                enable = false
            }
            isDebuggable = false
            // Подпись только если ключ на месте (иначе релиз-сборка без ключа не падает на конфиге).
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true   // для BuildConfig.VERSION_NAME в футере
    }

    // libv2ray.aar лежит в app/libs; забираем и его нативные .so оттуда.
    sourceSets["main"].jniLibs.srcDirs("libs")

    // Разбиваем релиз по ABI: отдельный APK на архитектуру + универсальный (запасной).
    // Основной для раздачи — arm64-v8a; x86_64 нужен лишь эмуляторам, armeabi-v7a — очень старым телефонам.
    // versionCode НЕ смещаем по ABI (все APK = code 2) — прямая раздача файлами, не Play Store.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
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