plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.camera.camera.camera2"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":camera-api"))
    implementation(project(":camera-capability"))
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.kotlinx.coroutines.android)
}
