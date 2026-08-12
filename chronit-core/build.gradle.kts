plugins {
    id("chronit.java-conventions")
}

description = "Configuration, scheduling, orchestration and the version-agnostic client driver SPI"

dependencies {
    // `api` where the type genuinely appears in this module's public signatures, so consumers do
    // not have to guess which transitive dependencies they are allowed to touch.

    // ConfigLoader.mapper() hands back an ObjectMapper.
    api(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)

    // Microsoft account authentication. A core dependency rather than a driver one because auth is
    // protocol-version independent — the driver only ever sees a resolved AuthContext of plain JDK
    // types. TokenStore exposes Gson's JsonObject, which arrives with this.
    api(libs.minecraftauth)

    implementation(libs.cronutils) {
        // Only used for message templating in validation errors we never surface.
        exclude(group = "org.glassfish", module = "jakarta.el")
    }

    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
