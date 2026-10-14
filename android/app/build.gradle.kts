import java.net.URI
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
        versionCode = 14
        versionName = "0.8.0"
        // Debug builds talk to the laptop over USB (adb reverse); no key needed locally.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://localhost:8000\"")
        buildConfigField("String", "APP_KEY", "\"\"")
        // sherpa-onnx ships native code per CPU type; real phones are ARM. Keeps the APK small.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Compress native code (sherpa-onnx for "Hey Friday") inside the APK: a much smaller file to
    // share, unpacked once on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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

// "Hey Friday": sherpa-onnx's keyword spotter (Android release, ONNX Runtime linked in). It's
// 39 MB, so it isn't in git: the first build downloads it into app/libs.
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${sherpaAar.name}"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { output -> input.copyTo(output) } }
}

// Friday's voice: "Jenny (Dioco)", an Irish woman's voice (Piper VITS, int8; free to use, credit
// "Jenny (Dioco)") for sherpa-onnx's offline text-to-speech. Not in git: the first build downloads
// it (21 MB) and keeps the model and the English pronunciation rules in app/voice/friday-voice.
val voiceName = "vits-piper-en_GB-jenny_dioco-medium-int8"
val voiceDir = file("voice/friday-voice")
if (!voiceDir.resolve("model.onnx").exists()) {
    val tarball = layout.buildDirectory.file("$voiceName.tar.bz2").get().asFile
    tarball.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$voiceName.tar.bz2"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> tarball.outputStream().use { output -> input.copyTo(output) } }
    copy {
        from(tarTree(resources.bzip2(tarball)))
        include(
            "$voiceName/en_GB-jenny_dioco-medium.onnx", "$voiceName/tokens.txt", "$voiceName/MODEL_CARD",
            "$voiceName/espeak-ng-data/phon*", "$voiceName/espeak-ng-data/intonations",
            "$voiceName/espeak-ng-data/en_dict", "$voiceName/espeak-ng-data/lang/gmw/en*",
            "$voiceName/espeak-ng-data/voices/**",
        )
        eachFile { path = path.removePrefix("$voiceName/").replace("en_GB-jenny_dioco-medium.onnx", "model.onnx") }
        includeEmptyDirs = false
        into(voiceDir)
    }
}
android.sourceSets.getByName("main").assets.srcDir("voice")

dependencies {
    implementation(files(sherpaAar))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
