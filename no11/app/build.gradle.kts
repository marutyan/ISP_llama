plugins {
    alias(libs.plugins.kotlin.jvm)      // Kotlin/JVM
    application                         // CLI / GUI
}

repositories { mavenCentral() }

dependencies {
    // ─── テスト保持 ───
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ─── アプリ用 ───
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

application {
    // ←★ ここを代入式で指定すると run タスクが正しく認識
    mainClass = "kindai.example.AppKt"
}

tasks.named<Test>("test") { useJUnitPlatform() }
