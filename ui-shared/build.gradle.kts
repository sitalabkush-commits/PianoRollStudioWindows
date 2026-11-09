plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    // WHY not commonMain: core-model / core-music / core-audio-api are plain kotlin-jvm modules
    // (java.util.UUID, @Synchronized, ...). Kotlin common code cannot depend on a JVM-only module,
    // and changing those contracts is not allowed. So the UI code lives in ONE shared folder that is
    // compiled by both targets. Both are JVM targets, so core-* are visible. The desktop compile
    // enforces "no android.* in shared code". Moving to a real commonMain later = convert core-* to KMP first.
    val shared = "src/shared/kotlin"
    val sharedTest = "src/sharedTest/kotlin"

    sourceSets {
        val androidMain by getting {
            kotlin.srcDir(shared)
            dependencies {
                api(project(":core-model"))
                api(project(":core-audio-api"))
                api(project(":core-music"))
                api(project.dependencies.platform(libs.compose.bom))
                api(libs.compose.ui)
                api(libs.compose.foundation)
                api(libs.compose.material3)
            }
        }
        val desktopMain by getting {
            kotlin.srcDir(shared)
            dependencies {
                api(project(":core-model"))
                api(project(":core-audio-api"))
                api(project(":core-music"))
                api(compose.ui)
                api(compose.foundation)
                api(compose.material3)
            }
        }
        val androidUnitTest by getting {
            kotlin.srcDir(sharedTest)
            dependencies { implementation(kotlin("test")) }
        }
        val desktopTest by getting {
            kotlin.srcDir(sharedTest)
            dependencies { implementation(kotlin("test")) }
        }
    }
}

android {
    namespace = "com.pianoroll.ui"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
