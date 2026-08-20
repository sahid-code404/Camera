import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val devKeySource = rootProject.file("ci/dev-update-keystore.b64")
val devKeyFile = layout.buildDirectory.file("dev-signing/camera-dev-update.jks").get().asFile
if (devKeySource.exists()) {
    devKeyFile.parentFile.mkdirs()
    devKeyFile.writeBytes(Base64.getMimeDecoder().decode(devKeySource.readText()))
}

val ciBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val devVersionCode = ciBuildNumber ?: 2

android {
    namespace = "com.camera.app"
    compileSdk = 37

    defaultConfig {
        // Qualcomm/Xiaomi vendor camera stacks commonly whitelist this identity for aux cameras.
        // This is a development compatibility identity, not a production package name.
        applicationId = "org.codeaurora.snapcam"
        minSdk = 28
        targetSdk = 37
        versionCode = devVersionCode
        versionName = "0.2.0-dev.$devVersionCode"
        buildConfigField(
            "String",
            "OTA_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/sahid-code404/Camera/ota/update.json\"",
        )
    }

    signingConfigs {
        create("devUpdate") {
            storeFile = devKeyFile
            storePassword = "camera-dev-only"
            keyAlias = "camera-dev"
            keyPassword = "camera-dev-only"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Stable dev signing is required so each CI APK can update over the previous build.
            signingConfig = signingConfigs.getByName("devUpdate")
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":feature-camera"))
    implementation(project(":feature-settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
