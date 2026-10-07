import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Wear OS companion uses the same package name and signing key as the phone app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.johnfegan.plextouch.watch"
    compileSdk = 36

    defaultConfig {
        // Wear OS 3 and later.
        minSdk = 30
        targetSdk = 36
        // Watch APKs share the phone's package, so their version codes must never collide with the phone's.
        versionCode = 1_000_001
        versionName = "0.1.0"
        applicationId = "org.johnfegan.musicbooks"
        // The offline copy is a preview until it has been checked on a real watch.
        buildConfigField("boolean", "OFFLINE_PREVIEW", "true")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }

    lint {
        abortOnError = true
        warningsAsErrors = false
        enable += setOf("HardcodedText")
        checkDependencies = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":wear-shared"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.play.services.wearable)
    implementation(libs.gson)
    testImplementation(libs.junit)
}
