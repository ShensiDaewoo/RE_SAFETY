plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.contest.signalprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.contest.signalprobe.fig"
        // Pleos Connect requires minSdk 28 or higher.
        minSdk = 28
        targetSdk = 34
        versionCode = 24
        versionName = "0.8.0-r2d-dev"
        testInstrumentationRunner = "com.contest.signalprobe.CaseCInstrumentation"
    }

    // R2D: the submission track must stay inside R2C's permission and code scope; experiments live only in "lab".
    flavorDimensions += "track"
    productFlavors {
        create("submission") {
            dimension = "track"
            applicationId = "com.contest.signalprobe.r2d"
            buildConfigField("boolean", "NEARBY_SOS", "false")
            buildConfigField("boolean", "ACCIDENT_SIM", "false")
        }
        create("lab") {
            dimension = "track"
            applicationId = "com.contest.signalprobe.r2d.lab"
            buildConfigField("boolean", "NEARBY_SOS", "true")
            buildConfigField("boolean", "ACCIDENT_SIM", "true")
        }
    }
    buildFeatures { buildConfig = true }

    // Android Automotive car API (CarPropertyManager) from the SDK's optional library.
    useLibrary("android.car")

    testBuildType = "release"
    buildTypes {
        release {
            isMinifyEnabled = false
            // Measurement-only tool: sign release with the debug key so it installs without setup.
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
    // Official Pleos Vehicle SDK (public Maven repo listed in the Pleos docs).
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // D5 offline QR: ZXing core (Apache-2.0), pure Java, no network use at runtime.
    implementation("com.google.zxing:core:3.5.3")
    // D plan F2: vehicle position from the head unit (Pleos FusedLocation, public Maven). No INTERNET permission.
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    // Error codes (LocationException) ship at runtime with FusedLocation; needed at compile time too.
    compileOnly("ai.pleos.playground:fused-location-data:0.0.3")
    // D plan F3: road/address text from the head-unit navigation for the spoken location line.
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}

