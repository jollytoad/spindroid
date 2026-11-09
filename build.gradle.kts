// The Android Gradle plugin and the Kotlin plugins live on the buildscript
// classpath, so every module uses the same Kotlin version.
//
// AGP 9 has built-in Kotlin support, so the app module does not apply a
// Kotlin plugin. The protocol module applies the plain JVM Kotlin plugin.
//
// Version catalog accessors are not available inside a buildscript block, so
// the plugin versions are written out here.

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.5.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.13")
    }
}
