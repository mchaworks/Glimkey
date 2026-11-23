pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Liquid-Glass-Android (QWEA0), prebuilt AAR via JitPack
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.QWEA0") }
        }
    }
}
rootProject.name = "Pillboard"
include(":app")
