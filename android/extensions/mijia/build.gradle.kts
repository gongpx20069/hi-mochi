import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val mochiVersionName = providers.gradleProperty("mochiVersionName")
    .orElse("1.0.1")
    .get()
val mochiVersionMatch = Regex("""1\.0\.(\d+)""")
    .matchEntire(mochiVersionName)
    ?: error("mochiVersionName '$mochiVersionName' must match 1.0.x")
val mochiVersionPatch = mochiVersionMatch.groupValues[1].toInt()
require(mochiVersionPatch > 0) {
    "mochiVersionName patch must be greater than zero"
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.mochi_mijia"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mochi_pet.extension.mijia"
        minSdk = 26
        targetSdk = 36
        versionCode = 10_000 + mochiVersionPatch
        versionName = mochiVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    val signingPropertiesFile = rootProject.file("signing.properties")
    if (signingPropertiesFile.exists()) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(
                    signingProperties.getProperty("storeFile"),
                )
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
        buildTypes.named("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        informational += "GradleDependency"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":extension-api"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.zxing:core:3.5.4")
    // OkHttp 5.5 requires compileSdk 37, beyond the current AGP maximum.
    //noinspection NewerVersionAvailable
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    //noinspection NewerVersionAvailable
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    //noinspection NewerVersionAvailable
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
