pluginManagement {
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version").get()
    }

    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "smart-survival-architect"

include(
    ":architect-core",
    ":construction-core",
    ":minecraft-common",
    ":platform-fabric",
)
