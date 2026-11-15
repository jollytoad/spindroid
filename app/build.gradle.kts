// The application module. AGP 9 has built-in Kotlin support, so this module
// does not apply a Kotlin plugin.

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    id("com.android.application")
    // The Compose compiler. The version comes from the buildscript classpath,
    // because it has to match the Kotlin that AGP 9.5.0 brings.
    id("org.jetbrains.kotlin.plugin.compose")
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
        testInstrumentationRunner = "io.cucumber.android.runner.CucumberAndroidJUnitRunner"
    }

    sourceSets {
        // The Gherkin files stay in one place. They are assets of the test APK,
        // never of the app APK.
        getByName("androidTest").assets.srcDir(rootProject.file("bdd"))
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":sendspin-protocol"))
    implementation(project(":music-assistant-client"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.java.websocket)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.cucumber.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

/**
 * Runs the Gherkin suite on a connected device.
 *
 * The connected test task uninstalls the app when it ends. This task
 * installs the two APKs and starts the instrumentation itself, so the app
 * stays installed, and a person can start it again after the run.
 *
 * Android force-stops the app when the instrumentation ends, so the app is
 * closed when the task returns.
 *
 * The task also reads the output, because `am instrument` exits with zero
 * even when a scenario fails.
 *
 * Set `ANDROID_SERIAL` to choose a device when more than one is connected.
 */
abstract class RunBddTask : DefaultTask() {
    @get:InputFile
    abstract val adb: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun runSuite() {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            executable(adb.get().asFile.absolutePath)
            args(
                "shell",
                "am",
                "instrument",
                "-w",
                "com.sendspin.sender.test/io.cucumber.android.runner.CucumberAndroidJUnitRunner",
            )
            standardOutput = output
        }

        val text = output.toString()
        logger.lifecycle(text)
        if (!text.contains("OK (")) {
            throw GradleException("The BDD suite failed. Read the output above.")
        }
    }
}

tasks.register<RunBddTask>("runBdd") {
    group = "verification"
    description = "Runs the BDD suite on a connected device."
    dependsOn("installDebug", "installDebugAndroidTest")
    adb.set(androidComponents.sdkComponents.adb)
}
