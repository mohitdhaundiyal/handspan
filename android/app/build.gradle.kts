import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from android/keystore.properties (never committed):
//   storeFile=/absolute/path/to/upload-keystore.jks
//   storePassword=…
//   keyAlias=upload
//   keyPassword=…
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val privacyPolicyUrl = providers.gradleProperty("handspan.privacyPolicyUrl").getOrElse("")

android {
    namespace = "app.handspan.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.handspan.android"
        minSdk = 29
        // Target 37 also requires requesting Android 17's local-network permission.
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        resValue("string", "privacy_policy_url", privacyPolicyUrl)
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
}

// Guard against shipping a release without the store requirements in place.
val verifyReleaseConfig by tasks.registering {
    val url = privacyPolicyUrl
    val signed = keystoreProperties.isNotEmpty()
    doLast {
        check(url.startsWith("https://")) { "Set handspan.privacyPolicyUrl=https://… in android/gradle.properties" }
        check(signed) { "Create android/keystore.properties with the release key (see README)" }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseConfig)
}
