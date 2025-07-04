import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.0"
    id("org.jetbrains.changelog") version "2.2.0"
    kotlin("jvm") version "1.9.10"
    kotlin("plugin.serialization") version "1.9.10"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

intellij {
    version.set("2024.2.5")  // Match your gradle.properties platformVersion
    type.set("IC")
    plugins.set(listOf("java"))
    
    // Optimize for disk space
    downloadSources.set(false)
    instrumentCode.set(false)
}

changelog {
    version.set("${project.version}")
    path.set("${project.projectDir}/CHANGELOG.md")
}

tasks {
    patchPluginXml {
        changeNotes.set("Initial release")
    }
    
    // Optimize JVM memory usage
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"  // Match Java target
    }
}

// Configure Gradle to use less memory
tasks.withType<Test> {
    maxHeapSize = "1g"
}