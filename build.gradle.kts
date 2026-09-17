plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.vanillawhitelist"
version = "1.0.6-alpha"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    // 必须声明为输入，否则只改 version 时 Gradle 会判定 UP-TO-DATE，
    // 导致打进 jar 的 plugin.yml 版本号是旧的
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
}
