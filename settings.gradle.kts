pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PianoRollStudio"
include(
    ":core-model", ":core-music", ":core-audio-api", ":core-audio-engine", ":export",
    ":data-android", ":audio-android", ":ui-shared", ":app-android", ":app-desktop"
)
