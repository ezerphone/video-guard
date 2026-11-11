plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// מפתח חתימה קבוע. בלי זה כל בנייה ב-CI נחתמת במפתח debug חדש (ה-runner נוצר מחדש), ועדכון
// נכשל על חוסר התאמה בחתימה — ואי אפשר להסיר ולהתקין מחדש, כי האפליקציה Device Owner וחסומה להסרה.
// ה-CI מפענח את ה-secret VG_KEYSTORE_B64 לקובץ ומעביר את הנתיב והסיסמאות במשתני סביבה.
val vgKeystore: String? = System.getenv("VG_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "android.ezerphone.videoguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "android.ezerphone.videoguard"
        minSdk = 29
        targetSdk = 33
        versionCode = 7
        versionName = "2.1"
    }

    signingConfigs {
        if (vgKeystore != null) {
            create("vg") {
                storeFile = file(vgKeystore!!)
                storePassword = System.getenv("VG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VG_KEY_ALIAS") ?: "videoguard"
                keyPassword = System.getenv("VG_KEY_PASSWORD") ?: System.getenv("VG_KEYSTORE_PASSWORD")
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
            // האפליקציה לא זקוקה לחתימת פלטפורמה (היא מסתמכת על מיקום ב-/system + appops של root).
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
}
