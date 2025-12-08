plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.example"
version = "1.2 Beta"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {

        intellijIdeaCommunity("2024.3")

        bundledPlugin("com.intellij.java")

        zipSigner()
        pluginVerifier()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("243")
            untilBuild.set(provider { null })
        }
    }
}