import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("signing.properties")
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningConfigured = signingPropertiesFile.isFile

if (releaseSigningConfigured) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

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
        versionCode = 18
        versionName = "0.8.0"
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

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                // signing.properties is intentionally gitignored. A missing or incomplete file is
                // reported by verifyReleaseSigning before an APK can be packaged.
                storeFile = rootProject.file(signingProperties.getProperty("storeFile").orEmpty())
                storePassword = signingProperties.getProperty("storePassword").orEmpty()
                keyAlias = signingProperties.getProperty("keyAlias").orEmpty()
                keyPassword = signingProperties.getProperty("keyPassword").orEmpty()
            }
        }
    }

    buildTypes.named("release") {
        if (releaseSigningConfigured) {
            signingConfig = signingConfigs.getByName("release")
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

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Refuses unsigned release builds."
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Copy signing.properties.example to signing.properties and keep both it and the keystore outside version control."
        }
        val missing = releaseSigningKeys.filter { signingProperties.getProperty(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing is incomplete; missing: ${missing.joinToString()}."
        }
        check(rootProject.file(signingProperties.getProperty("storeFile")).isFile) {
            "Release keystore does not exist at the storeFile path in signing.properties."
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease") {
        dependsOn("verifyReleaseSigning")
    }
}
