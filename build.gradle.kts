plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.ktor.plugin") version "3.2.3"
    application
}

group = "xyz.nikitacartes.mappinglens"
version = "0.1.0"

application {
    mainClass.set("xyz.nikitacartes.mappinglens.ApplicationKt")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    // Ktor
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-swagger")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Mappings
    implementation("net.fabricmc:mapping-io:0.7.1")

    // OpenAPI spec is authored in YAML; converted to JSON for /openapi.json
    implementation("org.yaml:snakeyaml:2.3")

    // Bytecode
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-util:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")

    // Source symbol resolution: map a cursor position in decompiled .java to owner/name/descriptor.
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.4")

    // Serialization & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// SKILL.md stays at .github/skills/mappinglens so GitHub finds it; the jar carries the same file
// on the classpath root, which /skill.md serves.
sourceSets.main {
    resources.srcDir(".github/skills/mappinglens")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kotlinx.coroutines.test.default.timeout", "180s")
}
