/**
 * Settings shared by every module.
 *
 * A convention plugin rather than a `subprojects { }` block in the root build: cross-project
 * configuration defeats the configuration cache and Gradle's isolated-projects work, and it makes
 * a module's build file stop telling the whole truth about that module.
 *
 * Deliberately declares no dependencies. Modules declare their own from the version catalog, where
 * the `libs` accessor works without the awkward workaround needed to reach a catalog from inside a
 * precompiled script plugin.
 */

plugins {
    // java-library rather than java, so modules can distinguish `api` from `implementation` and
    // a consumer's compile classpath only carries what it is actually meant to use.
    `java-library`
}

group = "dev.chronit"
version = "1.0.0-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
    // `--release` rather than a Java toolchain: it pins the API level to 21 while compiling with
    // whatever JDK is running Gradle. A toolchain would additionally pin the compiler JDK, which
    // sounds stricter but means the build cannot run at all on a machine without exactly that JDK
    // installed. The container build fixes the JDK anyway.
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            // -try is off because holding a lock through a try-with-resources lease, without
            // referencing the resource in the body, is deliberate and correct here.
            "-Xlint:all,-serial,-processing,-try",
            // Records need parameter names retained for Jackson to bind YAML without every
            // component carrying a @JsonProperty.
            "-parameters",
        )
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }

    // The protocol tests open real sockets to an in-process server. Running test classes in
    // parallel would have them competing for ports and CPU while asserting on timing.
    maxParallelForks = 1
}

tasks.withType<Jar>().configureEach {
    // Same inputs, same bytes — so a rebuild of an unchanged commit produces an identical jar.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
