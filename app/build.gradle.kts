import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Backend endpoints and identity-exchange credentials are never committed: they come from
// local.properties (preferred locally) or environment variables (CI), falling back to defaults.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String =
    localProperties.getProperty(key)
        ?: System.getenv(key.replace('.', '_').uppercase())
        ?: default

/** `https://host/api/` -> `wss://host/ws/` (the WebSocket root lives beside the REST root). */
fun wsBase(apiBase: String): String {
    val root = apiBase.trimEnd('/').removeSuffix("/api")
    val scheme = when {
        root.startsWith("https://") -> "wss://" + root.removePrefix("https://")
        root.startsWith("http://") -> "ws://" + root.removePrefix("http://")
        else -> root
    }
    return "$scheme/ws/"
}

fun com.android.build.api.dsl.ApplicationProductFlavor.backend(apiBase: String) {
    buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
    buildConfigField("String", "WS_BASE_URL", "\"${wsBase(apiBase)}\"")
    buildConfigField("String", "VF_API_BASE_URL", "\"${secret("lexken.vf.apiBase", "https://stage.lexkenems.com/api/")}\"")
    buildConfigField("String", "VF_CLIENT_ID", "\"${secret("lexken.vf.clientId")}\"")
    buildConfigField("String", "VF_CLIENT_SECRET", "\"${secret("lexken.vf.clientSecret")}\"")
    buildConfigField("String", "VF_AUTH_BYPASS", "\"${secret("lexken.vf.bypassKey")}\"")
    buildConfigField("String", "VF_BUNDLE_ID", "\"${secret("lexken.vf.bundleId", "com.lexken.mobile")}\"")
}

android {
    namespace = "com.lexken.lexkenai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lexken.lexkenai"
        minSdk = 24
        targetSdk = 37
        // CD passes the tag as the name and a monotonic build number; local builds keep the defaults.
        versionCode = (findProperty("lexken.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("lexken.versionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "backend"
    productFlavors {
        create("staging") {
            dimension = "backend"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            backend(secret("lexken.staging.apiBase", "https://stage.lexken.ai/api/"))
        }
        create("prod") {
            dimension = "backend"
            backend(secret("lexken.apiBase", "https://lexken.ai/api/"))
        }
    }

    // The upload key never lives in the repository: CD decodes it from a secret and points
    // LEXKEN_KEYSTORE_PATH / LEXKEN_KEYSTORE_PASSWORD / LEXKEN_KEY_ALIAS / LEXKEN_KEY_PASSWORD at it.
    val storeKeystore = System.getenv("LEXKEN_KEYSTORE_PATH")
    signingConfigs {
        create("store") {
            if (storeKeystore != null) {
                storeFile = file(storeKeystore)
                storePassword = System.getenv("LEXKEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LEXKEN_KEY_ALIAS")
                keyPassword = System.getenv("LEXKEN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // Debug-signed so local release builds install; the store key takes over when CD provides it.
            signingConfig = if (storeKeystore != null) signingConfigs.getByName("store") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
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
