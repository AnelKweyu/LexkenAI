import com.google.firebase.appdistribution.gradle.AppDistributionExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
    alias(libs.plugins.kover)
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
    // The payment rail, as the web's VITE_PAYMENT_PROVIDER: "paystack" (default) or "daraja".
    buildConfigField("String", "PAYMENT_PROVIDER", "\"${secret("lexken.paymentProvider", "paystack")}\"")
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

    testOptions {
        // JVM unit tests run on JUnit 5 (Jupiter); the instrumented suite stays on AndroidJUnit4.
        unitTests.all { it.useJUnitPlatform() }
        // Gradle Managed Devices for instrumented tests: `./gradlew pixel6api34StagingDebugAndroidTest`.
        managedDevices.localDevices {
            create("pixel6api34") {
                device = "Pixel 6"
                apiLevel = 34
                systemImageSource = "aosp-atd"
                testedAbi = "x86_64"
            }
        }
    }
}

// Coverage (Kover, JaCoCo-free): the staging debug unit tests are the measured run. The UI shell,
// the DI wiring and generated classes are out; `core/` and every feature package are in, and the
// gate holds the line at the level P0 measured so a feature cannot land its code without its tests.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.lexken.lexkenai.MainActivity*",
                    "com.lexken.lexkenai.LexkenApplication",
                    "com.lexken.lexkenai.BuildConfig",
                    "com.lexken.lexkenai.di.*",
                    "com.lexken.lexkenai.ui.*",
                    "*ComposableSingletons*",
                    // DataStore-backed stores need a device; their in-memory twins carry the contract tests.
                    "com.lexken.lexkenai.core.auth.DataStoreTokenStore",
                    "com.lexken.lexkenai.core.network.aisession.DataStoreDeviceIdStore",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
        variant("stagingDebug") {
            verify {
                // P0 measured 79% before the DataStore exclusions; the bar only ever moves up.
                rule("line coverage of core and features") {
                    minBound(78)
                }
            }
        }
    }
}

// Firebase project lexken-ai registers the prod package only; the staging flavour (suffix .staging)
// has no google-services.json until it is added to the console, so a missing file is ignored.
googleServices {
    missingGoogleServicesStrategy = com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
}

// Firebase App Distribution: CI sets FIREBASE_SERVICE_ACCOUNT_FILE (decoded secret) and, for the
// staging flavour, FIREBASE_APP_ID (the console's app id for com.lexken.lexkenai.staging). Prod
// resolves its app id from google-services.json.
configure<AppDistributionExtension> {
    artifactType = "APK"
    System.getenv("FIREBASE_SERVICE_ACCOUNT_FILE")?.let { serviceCredentialsFile = it }
    System.getenv("FIREBASE_APP_ID")?.let { appId = it }
    groups = System.getenv("FIREBASE_TESTER_GROUPS") ?: "testers"
    releaseNotes = System.getenv("FIREBASE_RELEASE_NOTES") ?: ("Build " + (System.getenv("GITHUB_SHA")?.take(7) ?: "local"))
}

dependencies {
    // Firebase, prod flavour only (Analytics; App Distribution is build-time).
    "prodImplementation"(platform(libs.firebase.bom))
    "prodImplementation"(libs.firebase.analytics)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // core: coroutines, serialization, DataStore
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    // core/network: Retrofit on OkHttp (REST + WebSocket)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DI
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
