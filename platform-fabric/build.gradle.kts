import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName = "terrainwright"
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

    include(project(":architect-core"))
    include(project(":construction-core"))
    include(project(":minecraft-common"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val releaseJar = layout.buildDirectory.file("libs/terrainwright-${project.version}.jar")

tasks.register("verifyReleaseJarContents") {
    group = "verification"
    description = "Verifies that the release JAR contains every internal runtime module"
    dependsOn(tasks.named("jar"))
    inputs.file(releaseJar)

    doLast {
        val requiredClasses = mapOf(
            "architect-core" to "dev/ssa/architect/material/MaterialRole.class",
            "construction-core" to "dev/ssa/construction/job/BuildJob.class",
            "minecraft-common" to "dev/ssa/common/permission/PermissionPort.class",
        )

        ZipFile(releaseJar.get().asFile).use { outerJar ->
            for ((moduleName, requiredClass) in requiredClasses) {
                val nestedJar = outerJar.entries().asSequence().firstOrNull { entry ->
                    entry.name.startsWith("META-INF/jars/$moduleName-") && entry.name.endsWith(".jar")
                }
                checkNotNull(nestedJar) {
                    "Release JAR is missing the nested $moduleName runtime module"
                }

                var classFound = false
                ZipInputStream(outerJar.getInputStream(nestedJar)).use { nestedEntries ->
                    while (true) {
                        val entry = nestedEntries.nextEntry ?: break
                        if (entry.name == requiredClass) {
                            classFound = true
                            break
                        }
                    }
                }
                check(classFound) {
                    "Nested $moduleName runtime module is missing $requiredClass"
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyReleaseJarContents")
}
