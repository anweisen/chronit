rootProject.name = "chronit"

include(
  "chronit-core",
  "chronit-driver-mcpl",
  "chronit-web",
  "chronit-via",
  "chronit-app",
)

dependencyResolutionManagement {
  // Repositories are declared once, here, rather than per project. Projects that try to add
  // their own fail the build, which keeps resolution predictable across modules.
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

  repositories {
    mavenCentral()

    // Each third-party repository is restricted to the groups it actually serves. Without
    // this, Gradle queries every repository for every dependency — slower, and it means a
    // compromised or typo-squatted coordinate on one of them could shadow a Maven Central
    // artifact.
    // MCProtocolLib itself, plus the NBT, maths and fastutil forks it pulls in, none of which
    // are on Maven Central.
    maven("https://repo.opencollab.dev/maven-snapshots/") {
      name = "opencollabSnapshots"
      content {
        includeGroupAndSubgroups("org.geysermc")
        includeGroupAndSubgroups("org.cloudburstmc")
        includeGroupAndSubgroups("com.nukkitx")
      }
    }
    maven("https://repo.opencollab.dev/main/") {
      name = "opencollab"
      content {
        includeGroupAndSubgroups("org.geysermc")
        includeGroupAndSubgroups("org.cloudburstmc")
        includeGroupAndSubgroups("com.nukkitx")
      }
    }
    maven("https://repo.viaversion.com") {
      name = "viaVersion"
      content {
        includeGroupAndSubgroups("com.viaversion")
        includeGroup("net.raphimc")
      }
    }
  }
}
