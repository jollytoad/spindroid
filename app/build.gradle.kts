// The application module. AGP 9 has built-in Kotlin support, so this module
// does not apply a Kotlin plugin.

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    // The Compose compiler. The version comes from the buildscript classpath,
    // because it has to match the Kotlin that AGP 9.5.0 brings.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.gubbins.spindroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gubbins.spindroid"
        minSdk = 33
        targetSdk = 36
        // CI passes -Pnightly.versionCode and -Pnightly.versionName, so each
        // nightly build has a growing number and a calendar version, see
        // https://calver.org. A build without the properties keeps
        // versionCode 1 and versionName 0.1.0.
        // See .github/workflows/nightly.yml.
        versionCode = providers.gradleProperty("nightly.versionCode")
            .map(String::toInt)
            .getOrElse(1)
        versionName = providers.gradleProperty("nightly.versionName")
            .getOrElse("0.1.0")
        testInstrumentationRunner = "io.cucumber.android.runner.CucumberAndroidJUnitRunner"
    }

    sourceSets {
        // The Gherkin files stay in one place. They are assets of the test APK,
        // never of the app APK. The account file is not an asset: it holds a
        // password, and Gradle reads it for the device command instead.
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

    androidResources {
        // The account file sits beside the Gherkin files, and the suite task
        // reads it for the device command. It is not an asset, because it
        // holds a password.
        ignoreAssetsPatterns.add("credentials.properties")
    }

    testOptions {
        // The unit tests read the rules of the app over JUnit 5, like the
        // other modules do.
        unitTests.all { unitTest ->
            unitTest.useJUnitPlatform()
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":sendspin"))
    implementation(project(":mass"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.java.websocket)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.cucumber.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.uiautomator)
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
 *
 * Pass `-Pbdd.tags=<filter>` to run a part of the suite. A plain run with no
 * filter runs every scenario in one pass, so prefer that when a Music
 * Assistant and an account are on hand. The scenarios that need a Music
 * Assistant are tagged `@requires-music-assistant`, so
 * `-Pbdd.tags="not @requires-music-assistant"` runs the suite on a network
 * without one. Cucumber reports no tests in that case, which passes.
 *
 * The scenarios that sign in need an account. The task reads it from
 * `bdd/credentials.properties`, which is not in the repository, and
 * `-Pbdd.ma.user` and `-Pbdd.ma.password` override the file. A run without
 * an account skips those scenarios, so the suite still passes.
 */
abstract class RunBddTask : DefaultTask() {
    @get:InputFile
    abstract val adb: RegularFileProperty

    /** The Cucumber tag filter, or no filter for the whole suite. */
    @get:Input
    @get:Optional
    abstract val tags: Property<String>

    /** The account file, or nothing when the machine has none. */
    @get:InputFile
    @get:Optional
    abstract val credentialsFile: RegularFileProperty

    /** The user name that overrides the account file, if any. */
    @get:Input
    @get:Optional
    abstract val user: Property<String>

    /** The password that overrides the account file, if any. */
    @get:Input
    @get:Optional
    abstract val password: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun runSuite() {
        val output = ByteArrayOutputStream()
        val filter = tags.orNull?.takeIf { it.isNotBlank() }
        val stored = Properties().apply {
            credentialsFile.orNull?.asFile?.inputStream()?.use { load(it) }
        }
        val accountUser = user.orNull?.takeIf { it.isNotBlank() }
            ?: stored.getProperty("ma.user")?.takeIf { it.isNotBlank() }
        val accountPassword = password.orNull?.takeIf { it.isNotBlank() }
            ?: stored.getProperty("ma.password")?.takeIf { it.isNotBlank() }
        val command = buildString {
            append("am instrument -w")
            if (filter != null) {
                append(" -e tags ")
                append(shellQuote(filter))
            }
            if (accountUser != null && accountPassword != null) {
                append(" -e bdd.user ")
                append(shellQuote(accountUser))
                append(" -e bdd.password ")
                append(shellQuote(accountPassword))
            }
            append(" app.gubbins.spindroid.test/io.cucumber.android.runner.CucumberAndroidJUnitRunner")
        }
        val adbFile = adb.get().asFile

        // The steps drive the screen, so the permissions that the screen
        // would otherwise ask for are in place before the run. The grants
        // fail when they are already in place, and nothing changes then.
        grantQuietly(
            adbFile,
            "pm grant app.gubbins.spindroid android.permission.POST_NOTIFICATIONS",
        )
        grantQuietly(
            adbFile,
            "pm grant app.gubbins.spindroid android.permission.RECORD_AUDIO",
        )
        grantQuietly(
            adbFile,
            "cmd notification allow_listener app.gubbins.spindroid/" +
                "app.gubbins.spindroid.capture.PlayingAppsListener",
        )
        execOperations.exec {
            executable(adb.get().asFile.absolutePath)
            // The command is one string, because adb joins separate
            // arguments without the quotes that the device shell needs.
            args("shell", command)
            standardOutput = output
        }

        val text = output.toString()
        logger.lifecycle(text)
        if (!text.contains("OK (")) {
            throw GradleException("The BDD suite failed. Read the output above.")
        }
    }

    /** Wraps a value for the shell on the device. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Runs a grant on the device, and keeps silent about the failure.
     *
     * A granted permission answers with an error, and the error means
     * that the grant is in place already.
     */
    private fun grantQuietly(adbFile: File, command: String) {
        try {
            execOperations.exec {
                executable(adbFile)
                args("shell", command)
            }
        } catch (exception: Exception) {
            // The grant may be in place, or the device may be known to
            // need it. The scenarios fail with a clear step otherwise.
            logger.lifecycle("Pre-run permission ignored: ${exception.message}")
        }
    }
}

tasks.register<RunBddTask>("runBdd") {
    group = "verification"
    description = "Runs the BDD suite on a connected device."
    dependsOn("installDebug", "installDebugAndroidTest")
    adb.set(androidComponents.sdkComponents.adb)
    tags.set(providers.gradleProperty("bdd.tags"))
    user.set(providers.gradleProperty("bdd.ma.user"))
    password.set(providers.gradleProperty("bdd.ma.password"))
    val credentials = rootProject.file("bdd/credentials.properties")
    if (credentials.exists()) {
        credentialsFile.set(credentials)
    }
}
