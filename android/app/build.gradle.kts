import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

val mochiVersionName = providers.gradleProperty("mochiVersionName")
    .orElse("1.0.1")
    .get()
val mochiVersionMatch = Regex("""1\.0\.(\d+)""")
    .matchEntire(mochiVersionName)
    ?: error(
        "mochiVersionName '$mochiVersionName' must match 1.0.x",
    )
val mochiVersionPatch = mochiVersionMatch.groupValues[1].toInt()
require(mochiVersionPatch > 0) {
    "mochiVersionName patch must be greater than zero"
}

plugins {
    id("com.android.application")
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.mochi_pet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mochi_pet"
        minSdk = 26
        targetSdk = 36
        versionCode = 10_000 + mochiVersionPatch
        versionName = mochiVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
                storePassword =
                    signingProperties.getProperty("storePassword")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        informational += "GradleDependency"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val sherpaOnnxAar =
    layout.projectDirectory.file("libs/sherpa-onnx-1.13.2.aar").asFile
val downloadSherpaOnnx by tasks.registering {
    outputs.file(sherpaOnnxAar)
    doLast {
        val expectedSha256 =
            "aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245"
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (sherpaOnnxAar.exists() && sha256(sherpaOnnxAar) == expectedSha256) {
            return@doLast
        }

        sherpaOnnxAar.parentFile.mkdirs()
        val temporaryFile = File(sherpaOnnxAar.path + ".download")
        temporaryFile.delete()
        val connection =
            uri(
                "https://api.github.com/repos/k2-fsa/sherpa-onnx/" +
                    "releases/assets/419292871",
            ).toURL().openConnection()
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "hi-mochi-build")
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.getInputStream().use { input ->
            temporaryFile.outputStream().use(input::copyTo)
        }
        check(sha256(temporaryFile) == expectedSha256) {
            "Downloaded sherpa-onnx AAR failed SHA-256 verification."
        }
        Files.move(
            temporaryFile.toPath(),
            sherpaOnnxAar.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadSherpaOnnx)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.javascriptengine:javascriptengine:1.1.0")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(files(sherpaOnnxAar))

    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
