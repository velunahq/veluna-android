import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veluna.music"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("velunaVersionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("velunaVersionName") as? String)?.takeIf { it.isNotBlank() } ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            val keystoreFile = file("${rootDir}/debug.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val path = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            val keystoreFile = file("${rootDir}/debug.keystore")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        error += "HardcodedText"
    }

    sourceSets {
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Room's exported schema is consumed by MigrationTestHelper from debug assets.
// Generate it before Gradle snapshots those assets for the unit-test variant, and serialize KSP
// invocations so debug and release tasks never write room.schemaLocation simultaneously.
abstract class RoomSchemaLock : org.gradle.api.services.BuildService<org.gradle.api.services.BuildServiceParameters.None>

val roomSchemaLock = gradle.sharedServices.registerIfAbsent("roomSchemaLock", RoomSchemaLock::class.java) {
    maxParallelUsages.set(1)
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn("kspDebugKotlin")
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
    usesService(roomSchemaLock)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}

tasks.withType<Test> {
    failFast = true
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("passed", "failed", "skipped")
    }
}
