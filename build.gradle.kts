plugins {
    id("net.fabricmc.fabric-loom") apply false
}

allprojects {
    group = property("maven_group") as String
    version = property("mod_version") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.14.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
