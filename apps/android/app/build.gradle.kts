plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.cim.securecall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cim.securecall"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // Phones (arm) and the x86_64 emulator; drops 32-bit x86 to keep the APK smaller.
        // `-Pabis=arm64-v8a` builds a single-ABI APK small enough to send to a phone.
        val abis = (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.webrtc)
}
