plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.p38.anclab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.p38.anclab"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-rebuild"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
