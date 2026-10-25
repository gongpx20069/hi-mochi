import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val mochiVersionName = providers.gradleProperty("mochiVersionName").orElse("1.0.1").get()
val patch = requireNotNull(Regex("""1\.0\.([1-9]\d*)""").matchEntire(mochiVersionName)).groupValues[1].toInt()

android {
    namespace = "com.example.mochi_webdav"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.mochi_pet.extension.webdav"
        minSdk = 26
        targetSdk = 37
        versionCode = 10_000 + patch
        versionName = mochiVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.exists()) {
        val properties = Properties().apply { signingFile.inputStream().use(::load) }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
        buildTypes.named("release") { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    bundle { language { enableSplit = false } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        informational += "GradleDependency"
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":extension-api"))
    implementation(project(":extension-ui"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    //noinspection NewerVersionAvailable
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    //noinspection NewerVersionAvailable
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.8.0-alpha01")
    testImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha01")
    //noinspection NewerVersionAvailable
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    //noinspection NewerVersionAvailable
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
    //noinspection NewerVersionAvailable
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
