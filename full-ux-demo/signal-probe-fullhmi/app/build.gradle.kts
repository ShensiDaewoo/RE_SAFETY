plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// RE:SAFETY Full HMI (WebView). It does not replace or cover any Pleos system screen.
// aurora13: on a Pleos head unit protect mode uses the Pleos Vehicle SDK (code carried over from the R2D submission app);
// on an ordinary Android device or emulator the page keeps the aurora12 simulation.
android {
    namespace = "com.contest.resafety.fullhmi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.contest.resafety.fullhmi"
        applicationIdSuffix = ".aurora"             // aurora design build (hardening6 functions + new design): installs next to the other builds
        minSdk = 28
        targetSdk = 34
        versionCode = 59
        versionName = "0.6.4-adaptive10"
    }

    // Android Automotive car API (CarPropertyManager: gear, speed) from the SDK's optional library, as in R2D.
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = false
            // Demonstration build: signed with the debug key so it installs without setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Offline QR for the incident summary (Apache-2.0). Same library and version as the R2D native app.
    implementation("com.google.zxing:core:3.5.3")
    // aurora13: official Pleos Vehicle SDK, the same version as the R2D submission app.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // aurora14: vehicle position, navigation address (same versions as R2D) and the Pleos TextToSpeech SDK (on-device mode).
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    compileOnly("ai.pleos.playground:fused-location-data:0.0.3")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // adaptive6: Pleos SpeechToText SDK (on-device mode), voice input on a tap only.
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
}
