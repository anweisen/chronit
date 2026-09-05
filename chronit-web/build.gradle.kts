plugins {
  id("chronit.java-conventions")
}

description = "Optional status and login web interface"

// Built on the JDK's com.sun.net.httpserver rather than an embedded servlet container. The whole
// interface is a handful of server-rendered pages; a framework would add several megabytes to the
// image and an API-churn risk for no benefit at this size.
dependencies {
  // WebInterface's constructor takes core types.
  api(project(":chronit-core"))

  implementation(libs.slf4j.api)

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
