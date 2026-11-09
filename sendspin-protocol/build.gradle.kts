// The protocol module is a plain Kotlin/JVM library. It has no Android
// dependency, so its tests run on the JVM without an emulator.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
