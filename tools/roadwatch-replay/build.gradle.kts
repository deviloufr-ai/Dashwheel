// Road watch's replay harness: a PC program that runs the very code the phone
// runs (LaneFinder, LeadTracker, RoadRules from :link) over a recorded drive,
// draws what it saw over every frame, and counts what it got right. Frames
// come and go through ffmpeg; the vehicle detector runs in detect.py beside
// it, with the phone's own model. See README.md.
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

application {
    applicationName = "roadreplay"
    mainClass.set("com.openauto.dash.replay.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

dependencies {
    implementation(project(":link"))
    testImplementation("junit:junit:4.13.2")
}
