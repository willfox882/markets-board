import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.marketsboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marketsboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.6.3"
    }

    // A FIXED debug key committed to the repo (standard alias and passwords, it protects nothing): every local and CI build is signed
    // with the same certificate, so an APK from any build can be installed over an earlier one and keeps the app's data.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // UI, models and core logic are shared with the desktop sandbox.
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
    sourceSets["main"].res.srcDir("../shared/res") // Geist font files (also used by the sandbox)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // The platform XmlPullParser on a phone; on the JVM unit-test classpath the real kxml2 classes stand in for the android.jar stubs.
    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
