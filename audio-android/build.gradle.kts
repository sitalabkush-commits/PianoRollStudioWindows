plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.pianoroll.audio"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Next step (FluidSynth/Oboe): add externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } } + abiFilters.
}
dependencies {
    api(project(":core-model"))
    api(project(":core-audio-api"))
    api(project(":core-audio-engine"))
    implementation(libs.kotlinx.coroutines.android)
}
