plugins {
    id("java")
    // MIGRATION: Updated to the new 2.x plugin standard required for 2024.2+
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.example"
version = "4.1-SNAPSHOT"

repositories {
    mavenCentral()
    // MIGRATION: New repository helper for 2.x
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // MIGRATION: IDE and Plugin dependencies move here in 2.x
    intellijPlatform {
        // Target IDE Version (Stable 2024.3)
        intellijIdeaCommunity("2024.3")

        // Required for Java PSI (PsiMethod, PsiClass, etc.)
        bundledPlugin("com.intellij.java")

        // Essential tools for the build process
        zipSigner()
        pluginVerifier()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// MIGRATION: Plugin metadata configuration
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("243")
            // Removes the upper build limit so it works on 2025.x
            untilBuild.set(provider { null })
        }
    }
}