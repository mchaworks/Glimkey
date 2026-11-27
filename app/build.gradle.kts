plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.pillboard.keyboard"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.pillboard.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        // same code as debug, but not debuggable: ART optimises it fully, so the keyboard runs smoother and lighter
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false }
}
dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.QWEA0:liquidglass:v2.0.11")
}
