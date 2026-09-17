plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.chinmaydash007.mobilebuild.preferences.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chinmaydash007.mobilebuild.preferences.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    debugImplementation(project(":preference-debug-bridge"))
    debugImplementation("androidx.security:security-crypto:1.1.0-alpha06")
}
