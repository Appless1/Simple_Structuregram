plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.2"
}

// Group ID matching your plugin.xml
group = "com.structuregram.simple"
version = "3.0-SNAPSHOT"

repositories {
    mavenCentral()
}

intellij {

    version.set("2024.2")


    type.set("IC")

    plugins.set(listOf("com.intellij.java"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    withType<JavaCompile> {
        options.release.set(17)
    }

    patchPluginXml {
        sinceBuild.set("232")

        untilBuild.set(provider { null })
    }
}