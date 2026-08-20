plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.camera.processing.raw"
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
    implementation(project(":processing-api"))
    implementation(libs.androidx.core.ktx)
}
