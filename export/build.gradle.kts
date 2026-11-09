plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core-model"))
    api(project(":core-audio-api"))
    testImplementation(kotlin("test"))
    testImplementation(project(":core-audio-engine"))   // end-to-end render test (pure JVM)
}
