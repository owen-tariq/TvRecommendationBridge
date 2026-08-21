import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional signing config. If keystore.properties exists (local) or the
// equivalent env vars are set (CI), release builds are signed with it. If
// not, the release build is left unsigned rather than failing, so a fresh
// clone can still run `assembleRelease`.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.owentariq.tvhop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.owentariq.tvhop"
        // Every Google TV / Android TV device with the launcher this app
        // hooks into is well above this; 26 lets us ship adaptive icons.
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "TVHOP_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "TVHOP_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "TVHOP_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "TVHOP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingValue("storeFile", "TVHOP_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation("junit:junit:4.13.2")
}
