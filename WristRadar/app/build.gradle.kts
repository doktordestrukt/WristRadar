import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Version info lives here only; the UI reads it from BuildConfig.
val wrVersionCode = 4
val wrVersionName = "4.0.0"
val wrReleaseLabel = "V4"

// Optional release signing: create keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without it, only debug is signed.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.wristradar"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.wristradar"
        minSdk = 33          // Wear OS 4+ (Galaxy Watch FE ships Wear OS 4/5, updatable to 6)
        targetSdk = 36
        versionCode = wrVersionCode
        versionName = wrVersionName
        buildConfigField("String", "RELEASE_LABEL", "\"$wrReleaseLabel\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ""
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
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.wear.compose:compose-material:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear.compose:compose-navigation:1.5.6")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")

    implementation("androidx.wear:wear-input:1.2.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    // Watch tile + watch-face complication ("glance" at your friend)
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Google Tink: maintained, audited primitives (X25519, Ed25519, HKDF).
    implementation("com.google.crypto.tink:tink-android:1.18.0")
    // BouncyCastle: maintained ML-KEM-768 (FIPS 203) for the hybrid post-quantum key exchange.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    // Only used to read whether the watch is connected to its phone (no data is sent).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5") // play-services pulls an old Fragment otherwise

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
