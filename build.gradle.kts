plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
intellij {
    version.set("2023.2.5") // Target IDE version
    type.set("IC") // IntelliJ Community Edition
    plugins.set(listOf("com.intellij.java")) // Vital: Adds Java PSI support
}

// FIX: Use your installed Java 21, but cross-compile to 17
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    // Set the JVM compatibility for the plugin
    withType<JavaCompile> {
        // options.release forces the compiler to generate bytecode compatible with Java 17
        // even though it is running on Java 21.
        options.release.set(17)
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("242.*")
    }

    // Signing and publishing blocks removed for local testing
}