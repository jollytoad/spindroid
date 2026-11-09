// The application module. AGP 9 has built-in Kotlin support, so this module
// does not apply a Kotlin plugin.

plugins {
    id("com.android.application")
}

android {
    namespace = "com.sendspin.sender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sendspin.sender"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // The debug build signs with a key that lives in the project, not in
        // the user's home directory. The build tool otherwise writes the key
        // to `~/.android`, which is not always available. The key signs
        // debug builds only, so it carries no secret. A release build uses
        // its own key, which is never checked in.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":sendspin-protocol"))
}
