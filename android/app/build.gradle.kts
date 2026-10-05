import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release settings and secrets live in android/vakya.properties (git-ignored):
//   serverUrl=https://<your-app>.onrender.com
//   appKey=<same value as VAKYA_APP_KEY on the server>
//   storeFile=keystore/vakya-release.jks, storePassword=..., keyAlias=vakya, keyPassword=...
val vakya = Properties().apply {
    rootProject.file("vakya.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun vakyaProp(name: String) = vakya.getProperty(name, "").trim()

android {
    // Kotlin packages stay com.replybot; only the installed app's identity is new.
    namespace = "com.replybot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onezerolabs.vakya"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Debug builds talk to the laptop over USB (adb reverse); no key needed locally.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://localhost:8000\"")
        buildConfigField("String", "APP_KEY", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (vakyaProp("storeFile").isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(vakyaProp("storeFile"))
                storePassword = vakyaProp("storePassword")
                keyAlias = vakyaProp("keyAlias")
                keyPassword = vakyaProp("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"${vakyaProp("serverUrl")}\"")
            buildConfigField("String", "APP_KEY", "\"${vakyaProp("appKey")}\"")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// A release build without a server address would ship an app that can't reach anything.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (vakyaProp("serverUrl").isEmpty() || vakyaProp("appKey").isEmpty()) {
            throw GradleException("Set serverUrl and appKey in android/vakya.properties before a release build.")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
