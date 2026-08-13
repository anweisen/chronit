plugins {
    id("chronit.java-conventions")
    alias(libs.plugins.shadow)
    application
}

description = "Command line entry point and executable jar"

/**
 * Opt-in legacy support: `./gradlew -Pvia build` bundles the ViaVersion translation layer, which
 * the driver then discovers through ServiceLoader. Without it the jar speaks one Minecraft version
 * and is several megabytes smaller.
 */
val withVia = providers.gradleProperty("via").map { it != "false" }.getOrElse(false)

dependencies {
    implementation(project(":chronit-core"))
    implementation(project(":chronit-driver-mcpl"))
    implementation(project(":chronit-web"))

    if (withVia) {
        implementation(project(":chronit-via"))
    }

    implementation(libs.picocli)
    runtimeOnly(libs.logback.classic)
    // The redaction converter is compiled against logback's API, not just running on it.
    compileOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "net.anweisen.chronit.app.Chronit"
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "chronit",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    archiveBaseName = "chronit"
    // No classifier or version, so the Dockerfile and the docs can name one stable path.
    archiveClassifier = ""
    archiveVersion = ""

    // The driver and the optional Via module both register ServiceLoader providers. Without
    // merging, one service file silently overwrites the other and discovery quietly stops working.
    //
    // The duplicates strategy has to allow duplicates through for the transformer to see them at
    // all — with the default, entries are dropped before merging and the merge quietly produces a
    // partial file. Netty and the maths library also ship service files that must survive intact.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    exclude(
        // Signatures are invalid once contents are repackaged, and a jar with stale signature
        // files fails to load.
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "module-info.class",
        // Build metadata with no runtime function. Excluding it also removes most of the duplicate
        // entries that INCLUDE would otherwise pull in from Netty's many artifacts.
        "META-INF/maven/**",
        "META-INF/native-image/**",
        "META-INF/INDEX.LIST",
        "META-INF/DEPENDENCIES",
    )

    manifest {
        attributes(
            "Main-Class" to "net.anweisen.chronit.app.Chronit",
            "Implementation-Title" to "chronit",
            "Implementation-Version" to project.version,
            "Chronit-Via-Bundled" to withVia.toString(),
        )
    }
}

// `build` should produce the runnable artifact, since that is what the container copies.
tasks.build {
    dependsOn(tasks.shadowJar)
}

// The application plugin is here for the `run` task and the main-class wiring. Its distribution
// archives are not — building four archives to ship one jar is just slower.
listOf("distTar", "distZip", "shadowDistTar", "shadowDistZip").forEach { name ->
    tasks.named(name) { enabled = false }
}

// The plain `run` task is handy for local work: ./gradlew :chronit-app:run --args="validate"
tasks.named<JavaExec>("run") {
    // Without this, a command that reads from the terminal has nothing attached.
    standardInput = System.`in`
}
