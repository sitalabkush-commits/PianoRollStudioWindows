import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"   // MSI needs MAJOR.MINOR.BUILD, MAJOR >= 1

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-music"))
    implementation(project(":core-audio-api"))
    implementation(project(":core-audio-engine"))
    implementation(project(":export"))
    implementation(project(":ui-shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)   // Dispatchers.Main for Compose Desktop
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.pianoroll.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "PianoRollStudio"
            packageVersion = appVersion
            description = "PianoRoll Studio - compose on a piano roll"
            vendor = "PianoRoll Studio"
            modules("java.desktop", "java.logging", "jdk.unsupported")   // java.desktop = javax.sound + AWT dialogs
            windows {
                menuGroup = "PianoRoll Studio"
                shortcut = true
                dirChooser = true
                perUserInstall = true                                      // no admin rights needed
                upgradeUuid = "6f1b8f0e-3c52-4d0a-9a43-7c1d2b6a9e11"       // keep constant: lets new MSIs upgrade old ones
            }
        }
    }
}

// Portable build: zip of the jpackage "app image" (createDistributable). No WiX needed.
val portableZip by tasks.registering(Zip::class) {
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("PianoRollStudio-portable-$appVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
}
