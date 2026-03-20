plugins {
    id("com.android.application")
}

android {
    namespace = "com.tailscale.mobile.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tailscale.mobile.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { viewBinding = true }
}

dependencies {
    implementation(project(":lib"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RootEncoder: GenericStream, Camera2Source, MicrophoneSource
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.7")
}
