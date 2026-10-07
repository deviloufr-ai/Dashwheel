// Dashwheel Companion: the small app on the driver's phone that shares its
// notifications (and later calls) with the launcher over the phone's hotspot.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openauto.dash.companion"
    compileSdk = 35

    // Same release key as the launcher (see app/build.gradle.kts), supplied by CI.
    val keystorePath = providers.environmentVariable("KEYSTORE_FILE").orNull
    val releaseKeystore = keystorePath?.let { file(it) }?.takeIf { it.exists() }
    val onCi = providers.environmentVariable("CI").orNull == "true"
    gradle.taskGraph.whenReady {
        val buildsRelease = allTasks.any { it.project == project && it.name.contains("Release") }
        if (onCi && releaseKeystore == null && buildsRelease) {
            throw GradleException("CI release build without a keystore: see app/build.gradle.kts.")
        }
    }

    defaultConfig {
        applicationId = "com.openauto.dash.companion"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "fr", "de", "es", "it", "pt", "nl", "pl")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":link"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Scans the pairing QR code the launcher shows.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // JVM unit tests (companion/src/test): telling a call notification's buttons apart.
    testImplementation("junit:junit:4.13.2")
}
