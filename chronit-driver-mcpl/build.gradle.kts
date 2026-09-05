// Imported because the bare `java` identifier resolves to the Java plugin extension inside a
// build script, which shadows the java.* package.
import java.time.Duration

plugins {
  id("chronit.java-conventions")
}

description = "MCProtocolLib implementation of the client driver SPI — the only version-aware module"

dependencies {
  // Implements the core SPI, so core's types are part of this module's public surface.
  api(project(":chronit-core"))

  // PipelineCustomizer exposes a Netty Channel, which arrives with this, and chronit-via extends
  // types from it.
  api(libs.mcprotocollib)

  // MCProtocolLib brings only the GSON component serializer. Chat patterns in the configuration
  // are matched against what a player would see on screen, which needs the plain-text renderer.
  implementation(libs.adventure.serializer.plain)

  implementation(libs.slf4j.api)

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testRuntimeOnly(libs.logback.classic)
}

tasks.test {
  // The protocol tests drive a scripted server through a full join sequence, including
  // deliberate timeouts.
  timeout = Duration.ofMinutes(10)
}
