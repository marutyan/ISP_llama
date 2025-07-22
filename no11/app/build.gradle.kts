plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.2"
}

group = "kindai.example"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose Multiplatform Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    
    // 既存の依存関係（API通信用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    
    // Kotlin標準ライブラリ
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    
    // テスト依存関係
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

compose.desktop {
    application {
        mainClass = "kindai.example.AppKt"
        
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "VoiceAI-App"
            packageVersion = "2.0.0"
            description = "🎙️ 音声認識AI アプリケーション - Modern UI Edition"
            copyright = "© 2025 Kindai University. All rights reserved."
            vendor = "Kindai University"
            
            macOS {
                bundleID = "jp.ac.kindai.info.voice-ai-app"
                dockName = "VoiceAI"
            }
        }
    }
} 