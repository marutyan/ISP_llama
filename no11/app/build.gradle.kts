plugins {
    kotlin("jvm") version "1.9.0"
    application
}

group = "kindai.example"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("kindai.example.AppKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
} 