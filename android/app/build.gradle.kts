import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.cookbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cookbook"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE (the run number) so each signed release installs cleanly over the
        // previous one; defaults to the last shipped value for local/debug builds.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 6
        versionName = System.getenv("VERSION_NAME") ?: "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String", "SERVER_URL",
            "\"${localProperties.getProperty("server.url", "https://cookbook.dragonflymedia.org/")}\""
        )
    }

    signingConfigs {
        // A stable, committed key so every build — debug, local release, CI release — shares one
        // signing identity. New APKs install over the top of existing ones without Android
        // complaining about INSTALL_FAILED_UPDATE_INCOMPATIBLE. Password is not secret.
        create("stable") {
            storeFile = file("cookbook-debug.keystore")
            storePassword = "cookbook01"
            keyAlias = "cookbook"
            keyPassword = "cookbook01"
        }
        // CI's real release key, only when KEYSTORE_PATH is supplied in the environment.
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // Prefer CI's release key; fall back to the stable committed key for local releases.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    listOf(
        "roborazzi.test.record",
        "roborazzi.test.verify",
        "roborazzi.test.compare",
    ).forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
    // The Robolectric NATIVE-graphics screenshot tests download a large android-all
    // runtime at test time, which can stall CI. Pass -PexcludeScreenshots to skip them
    // (the gating "Android — Unit Tests" job does this); they still run in the dedicated
    // screenshots job.
    if (project.hasProperty("excludeScreenshots")) {
        filter { excludeTestsMatching("com.cookbook.screenshot.*") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // PULSE design system (theme tokens + component kit), from the sibling Pulse repo via the
    // composite build declared in settings.gradle.kts.
    implementation(libs.pulse.ui)

    // Recipe images (v0.2) — remote URL loading with caching.
    implementation(libs.coil.compose)

    // Home-screen shopping-list widget (v0.3).
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.datastore.preferences)

    // Room: offline-first mirror of the shopping list + recipes read cache (CLAUDE.md §1, §7 Phase 4).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
