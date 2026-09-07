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
        versionCode = 12
        versionName = "0.5.7-rebuild"
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
    testImplementation("junit:junit:4.13.2")
}
