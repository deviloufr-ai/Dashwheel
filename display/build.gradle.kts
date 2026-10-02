// The second screen's half: a small JVM service for a Raspberry Pi wired to a
// monitor. It answers the head unit over the same encrypted link as the phone
// (:link), hands the H.264 it receives to GStreamer (hardware decode straight
// to the screen), and draws its own idle, pairing and fallback cluster screens.
// See tools/pi/README.md for installing it on the Pi.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

version = "1.0"

tasks.jar {
    // Sent to the head unit in DisplayHello.
    manifest { attributes("Implementation-Version" to project.version) }
}

application {
    applicationName = "dashwheel-display"
    mainClass.set("com.openauto.dash.display.MainKt")
    // A Pi 3 has 1 GB, shared with the GPU and the decoder: keep the heap small.
    applicationDefaultJvmArgs = listOf("-Xmx160m", "-XX:+UseSerialGC", "-Djava.awt.headless=true")
}

dependencies {
    implementation(project(":link"))
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
