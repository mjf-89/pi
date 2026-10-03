plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.pi.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.pi.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.1.6-poc"
        if (providers.gradleProperty("diagnostic").orNull == "true") {
            applicationIdSuffix = ".diagnostic"
            resValue("string", "app_name", "Pi Durable Diagnostics")
        } else {
            resValue("string", "app_name", "Pi Durable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("com.caoccao.javet:javet-node-android-i18n:6.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val bundlePi by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/bundle.mjs")
    // Pi's checkout is outside this project. Always regenerate to avoid stale source.
}
tasks.named("preBuild") { dependsOn(bundlePi) }
