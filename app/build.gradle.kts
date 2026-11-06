plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "android.ezerphone.videoguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "android.ezerphone.videoguard"
        minSdk = 29
        targetSdk = 33
        versionCode = 6
        versionName = "2.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            // נבנה עם מפתח ה-debug כדי שאפשר יהיה להתקין ישירות; האפליקציה לא זקוקה
            // לחתימת פלטפורמה (היא מסתמכת על מיקום ב-/system + appops של root).
            signingConfig = signingConfigs.getByName("debug")
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
