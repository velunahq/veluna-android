pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "veluna"
include(
    ":app",
    ":innertube",
    ":paxsenixlyrics",
    ":kugou",
    ":betterlyrics",
    ":lrclib",
    ":simpmusic",
    ":youlyplus",
    ":shazamkit",
    ":artistvideo",
    ":canvas",
    ":velunacanvas",
    ":applecanvas",
    ":unison",
    ":core",
    ":lyrics",
    ":playback",
    ":usbaudio",
    ":metadata",
    ":dsp-core",
    ":audio-dsp",
    ":domain"
)


// Use a local copy of BravePipe Extractor.
// We assume, that veluna and BravePipe Extractor have the same parent directory.
// If this is not the case, please change the path in includeBuild().
//
// For this to work you also need to change the implementation in innertube/build.gradle.kts
// to one which does not specify a version.
// From:
//      implementation(libs.newpipe.extractor)
// To:
//      implementation("com.github.bravepipeproject:extractor")
// includeBuild("../BravePipeExtractor") {
//     dependencySubstitution {
//         substitute(module("com.github.bravepipeproject:extractor")).using(project(":extractor"))
//     }
// }
