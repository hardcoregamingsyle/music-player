plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jagat.musicplayer"
    compileSdk = 34

    // CI builds on a fresh VM each time, so AGP's auto-generated debug key
    // differed every run and Android refused to install a new APK over an old
    // one ("App not installed"). A fixed key makes updates install in place.
    // It is a throwaway personal-use key; anyone holding it could sign an APK
    // your phone would accept as an update, which only matters if you install it.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    // Distinguishable per build so the app can show which one is installed.
    val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

    defaultConfig {
        applicationId = "com.jagat.musicplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
