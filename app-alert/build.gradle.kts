import com.cloudmonitor.build.requireReleaseSigning

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// Auto versioning — computed ONCE in settings.gradle.kts and shared by both apps.
// ---------------------------------------------------------------------------
val appVersionCode: Int = gradle.extra["appVersionCode"] as Int
val appVersionName: String = gradle.extra["appVersionName"] as String

// The Google Services plugin is only applied when google-services.json is present.
// Without it the app still builds; FCM is simply disabled at runtime.
val hasGoogleServices = rootProject.file("app-alert/google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}


// ---------------------------------------------------------------------------
// Backend base URL. Override with -PbackendBaseUrl=... or the BACKEND_BASE_URL
// environment variable. It is baked into BuildConfig so the apps never need a
// settings screen for the very first connection.
// ---------------------------------------------------------------------------
val backendBaseUrl: String = (
    project.findProperty("backendBaseUrl") as String?
        ?: System.getenv("BACKEND_BASE_URL")
        ?: "https://cloud-monitor-api.pages.dev"
    )

android {
    namespace = "com.cloudalert"
    // compileSdk is 37 because the pinned AndroidX / Compose / Ktor releases publish AAR
    // metadata requiring minCompileSdk 37. It only affects which APIs are visible while
    // compiling; no API introduced after 36 is used in the source.
    // targetSdk stays 36 so Android 16 (API 36) remains the primary RUNTIME target and
    // the app opts into the Android 16 behaviour changes documented in README.md.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cloudalert" // NEVER change: in-place updates depend on it
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val material = requireReleaseSigning()
        if (material != null) {
            create("release") {
                storeFile = material.storeFile
                storePassword = material.storePassword
                keyAlias = material.keyAlias
                keyPassword = material.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 is disabled on purpose: a background service that receives FCM callbacks is
            // a reliability risk for no measurable gain on a system that only installs our APKs.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Firebase is used ONLY to deliver pushes. No status data is ever stored in Firebase.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
