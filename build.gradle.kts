/**
 * chronit — scheduled Minecraft Java Edition server check-in.
 *
 * Per-module configuration lives in each module's own build file; anything shared is in the
 * `chronit.java-conventions` plugin under buildSrc.
 */

plugins {
    // Applied to nothing at the root — declared here so the version resolves once for the module
    // that does apply it.
    alias(libs.plugins.shadow) apply false
}

tasks.register("printVersions") {
    description = "Shows the Minecraft version this build targets."
    group = "help"

    val minecraft = libs.versions.minecraft.get()
    val mcpl = libs.versions.mcpl.get()
    val viaVersion = libs.versions.viaversion.get()

    doLast {
        println("Minecraft:      $minecraft")
        println("MCProtocolLib:  $mcpl")
        println("ViaVersion:     $viaVersion (optional, -Pvia)")
    }
}
