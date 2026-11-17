import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------
// Versioning
// The version comes from `classvibe.version` (set automatically by the GitHub
// release workflow from the tag, e.g. tag v1.2.0 -> 1.2.0). Locally it falls back
// to the value in gradle.properties.
//
// versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + (beta number, or 99 for final)
//   1.1.0-beta.1 -> 1010001     1.1.0 -> 1010099     1.2.3 -> 1020399
// ---------------------------------------------------------------------------
val appVersion: String = (project.findProperty("classvibe.version") as String?)?.removePrefix("v") ?: "1.4.0"

fun versionCodeOf(name: String): Int {
    val core = name.substringBefore('-')
    val parts = core.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val pre = name.substringAfter('-', "")
    val preNumber = if (pre.isEmpty()) 99 else (Regex("""(\d+)""").find(pre)?.value?.toIntOrNull() ?: 1).coerceIn(1, 98)
    return parts[0] * 1_000_000 + parts[1] * 10_000 + parts[2] * 100 + preNumber
}

// GitHub repository used by the in-app updater ("owner/repo").
val githubRepo: String = (project.findProperty("classvibe.githubRepo") as String?) ?: "YOUR_GITHUB_USERNAME/ClassVibe"

// ---------------------------------------------------------------------------
// Release signing: read from environment variables (GitHub Actions) or from
// keystore.properties in the project root (local builds). Never commit secrets.
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("CLASSVIBE_KEYSTORE_FILE", "storeFile")
val hasReleaseKey = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.classvibe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.classvibe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("CLASSVIBE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("CLASSVIBE_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("CLASSVIBE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without a release key (e.g. a quick local build) fall back to the debug key.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Jetpack Compose + Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room (local database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (settings + message history)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager (background reminders, update checks)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // ML Kit on-device text recognition (bundled model -> fully offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // QR codes: generation (ZXing, offline), camera scanning via Google's code scanner
    // (no camera permission needed), and scanning QR screenshots from the gallery (offline).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
