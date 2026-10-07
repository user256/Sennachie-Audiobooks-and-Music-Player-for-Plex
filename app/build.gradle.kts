import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.johnfegan.plextouch"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.7.1"
        applicationId = "org.johnfegan.musicbooks"
        buildConfigField("boolean", "HOUSEHOLD_INTEGRATION", "false")
        buildConfigField("String", "PRODUCT_NAME", "\"Sennachie for Plex\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }

    lint {
        // Lint errors fail the build (the CI gate); existing warnings do not.
        abortOnError = true
        warningsAsErrors = false
        // Ticket 124: user-facing text lives in res/values*/strings.xml. HardcodedText covers XML resources; Compose code
        // is kept literal-free by review and the grep in the README's "Translations" section. MissingTranslation and
        // ExtraTranslation stay at their default (error) severity.
        enable += setOf("HardcodedText")
        // Ticket 140: the platform accessibility checks (the widget's RemoteViews layout and any future View code) are on and
        // fail the build, so an unlabelled image button or an unlabelled field cannot land. Compose code is covered by the
        // checks bundled with the Compose libraries and by AccessibilitySourceGuardTest.
        val accessibility = setOf("ContentDescription", "ClickableViewAccessibility", "KeyboardInaccessibleWidget", "LabelFor")
        enable += accessibility
        error += accessibility
        checkDependencies = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

composeCompiler {
    // Stability and skippability evidence: app/build/compose_reports/*-classes.txt and *-composables.txt.
    // Writing the reports costs nothing noticeable, so they are always on rather than behind a Gradle property.
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.gson)
    implementation(libs.androidx.work.runtime.ktx)
    // Ticket 141: the Wear OS companion's Data Layer link and the protocol shared with the :wear app.
    implementation(project(":wear-shared"))
    implementation(libs.play.services.wearable)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
