plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.mirror.core.webrtc"
    compileSdk = 36

    defaultConfig {
        minSdk = 26 // receiver floor; sender is 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.stream.webrtc)

    testImplementation(libs.junit)
}
