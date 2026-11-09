plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core-model"))
    api(project(":core-audio-api"))
    testImplementation(kotlin("test"))
}
