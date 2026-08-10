plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName = "smart-survival-architect"
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("smart_survival_architect") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "smart_survival_architect_gametest"
        enableGameTests = true
        enableClientGameTests = true
        eula = true
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    implementation(project(":architect-core"))
    implementation(project(":construction-core"))
    implementation(project(":minecraft-common"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
