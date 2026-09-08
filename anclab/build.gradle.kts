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
        versionCode = 27
        versionName = "0.6.9-visible-coexistent-discovery-dev"
    }

    signingConfigs {
        create("ancLabDev") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ancLabDev")
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
        }
    }
}

dependencies {
    // ANC v0.6.9 separates persistent-frequency observation from controller admission and allows safe discovery to coexist with telemetry.
    testImplementation("junit:junit:4.13.2")
}
