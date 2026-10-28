plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Firebase (push that wakes the phone for incoming calls) from app/google-services.json, read here
// instead of with the Google Services plugin: the same resource values it would generate.
// Without the file the app builds fine and calls only arrive while it is running.
@Suppress("UNCHECKED_CAST")
val firebaseValues: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    val root = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val info = root["project_info"] as Map<String, Any?>
    val client = (root["client"] as List<Map<String, Any?>>).first {
        val ci = it["client_info"] as Map<String, Any?>
        (ci["android_client_info"] as Map<String, Any?>)["package_name"] == "com.cim.securecall"
    }
    mapOf(
        "google_app_id" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"],
        "gcm_defaultSenderId" to info["project_number"],
        "project_id" to info["project_id"],
        "google_api_key" to (client["api_key"] as List<Map<String, Any?>>).first()["current_key"],
    ).filterValues { it != null }.mapValues { it.value.toString() }
} ?: emptyMap()

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.cim.securecall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cim.securecall"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.2"
        firebaseValues.forEach { (k, v) -> resValue("string", k, v) }
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

    // Compressed native libraries (WebRTC is most of the APK): a much smaller file to send to a phone,
    // extracted once at install.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        resValues = true
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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
}
