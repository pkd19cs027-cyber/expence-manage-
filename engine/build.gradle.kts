import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The engine is deliberately a pure-JVM module: no Android, no I/O, no clock,
// no randomness. Everything the app "understands" about Indian financial
// messages lives here so it can be unit-tested on a laptop in milliseconds.
//
// Bytecode targets 17 to match the Android module, but no toolchain is pinned
// so the build works on any JDK 17 or newer.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
