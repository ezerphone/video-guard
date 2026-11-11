plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// מפתח חתימה קבוע (מה-secrets ב-CI). בלעדיו כל בנייה נחתמת במפתח debug אקראי, ועדכון של
// האפליקציה המותקנת נכשל — והיא חסומה להסרה כ-Device Owner. ראה README.
val vgKeystore: String? = System.getenv("VG_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "android.ezerphone.videoguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "android.ezerphone.videoguard"
        minSdk = 29
        targetSdk = 33
        versionCode = 10
        versionName = "3.0"
    }

    signingConfigs {
        if (vgKeystore != null) {
            create("vg") {
                storeFile = file(vgKeystore!!)
                storePassword = System.getenv("VG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VG_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "videoguard"
                keyPassword = System.getenv("VG_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("VG_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            if (vgKeystore != null) signingConfig = signingConfigs.getByName("vg")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (vgKeystore != null) "vg" else "debug")
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
    // גישת root (libsu של topjohnwu)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    testImplementation("junit:junit:4.13.2")
}
