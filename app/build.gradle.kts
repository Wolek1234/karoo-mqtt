plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.hammerhead.karooexttemplate"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.hammerhead.karooexttemplate"
        minSdk = 23
        targetSdk = 34
        versionCode = 13
        versionName = "1.12"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.matching { it.name == "packageDebug" }.configureEach {
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val src = File(dir, "app-debug.apk")
        val dst = File(dir, "karooMQTT-${android.defaultConfig.versionName}.apk")
        if (src.exists()) src.renameTo(dst)
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.paho.mqtt)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
}
