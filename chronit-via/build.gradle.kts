plugins {
    id("chronit.java-conventions")
}

description = "Optional ViaVersion translation, letting the native client speak older server protocols"

// Entirely optional. chronit-core has no compile-time reference to anything here; it is discovered
// at runtime through ServiceLoader. Removing this module from settings.gradle.kts leaves a working
// single-version application.
dependencies {
    // Implements SPIs from both.
    api(project(":chronit-core"))
    api(project(":chronit-driver-mcpl"))

    implementation(libs.vialoader)
    implementation(libs.viaversion)
    implementation(libs.viabackwards)

    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
