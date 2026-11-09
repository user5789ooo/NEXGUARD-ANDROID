
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexguard.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexguard.companion.v2"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"https://sfygmyyupndinfyuivec.supabase.co\""
        )

        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"sb_publishable_7j84isDkIbhGr0WuRS6UYQ_W-V4N8oe\""
        )
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("android/app/src/main/AndroidManifest.xml")

            java.srcDirs(
                "android/app/src/main/android/app/src/main/res/values/android/app/src/main/java"
            )

            res.srcDirs(
                "android/app/src/main/android/app/src/main/res"
            )
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
