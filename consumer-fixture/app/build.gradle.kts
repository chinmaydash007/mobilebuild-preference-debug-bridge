plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.chinmaydash007.mobilebuild.preferences.consumer"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chinmaydash007.mobilebuild.preferences.consumer"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

val bridgeVersion = providers.gradleProperty("bridgeVersion").orElse("1.0.0")

dependencies {
    debugImplementation(
        "io.github.chinmaydash007.mobilebuild:preference-debug-bridge:${bridgeVersion.get()}",
    )
}
