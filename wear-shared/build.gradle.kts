import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Ticket 141: the phone <-> watch protocol and the pure rules both sides apply. Plain JVM, so every rule is unit-tested
// without Android; the phone app (:app) and the watch app (:wear) both depend on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.gson)
    testImplementation(libs.junit)
}
