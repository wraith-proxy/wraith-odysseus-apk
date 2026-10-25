import java.util.Properties

// local.properties is gitignored (see local.properties.example). There is no
// server URL in here on purpose: the Odysseus address is typed in on the
// device, so the APK carries no hostname at all.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Order matters: local.properties first (a developer's own machine), then a
// gradle property (how CI or an F-Droid recipe injects a value), then the
// environment, then the default.
fun localProp(key: String, default: String): String =
    (localProps.getProperty(key)
        ?: (if (project.hasProperty(key)) project.property(key) as String else null)
        ?: System.getenv(key)
        ?: default)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "com.thomaspeoples.wraithodysseus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thomaspeoples.wraithodysseus"
        // Android 10+, unlike the single-device house app: this one is for
        // anyone running their own Odysseus server. No abiFilters -- the only
        // native code is AndroidX's ~9 KiB graphics-path library, so one
        // universal APK costs nothing.
        minSdk = 29
        targetSdk = 36
        // Both are literals because F-Droid's checkupdates reads them as
        // text at each android-* tag; a computed value is invisible to it.
        // Never edit by hand: on bump commitizen rewrites versionName and
        // bin/fdroid-bump.py rewrites versionCode as
        // major*10000 + minor*100 + patch (1.0.3 -> 10003), refusing
        // minor or patch >= 100 so codes stay monotonic. VersionCodeTest
        // fails the build if the two ever disagree.
        versionCode = 700
        versionName = "0.7.0"

        // false for an F-Droid build: F-Droid ships its own updates, and its
        // inclusion policy wants no phoning home it did not ask for.
        buildConfigField("boolean", "UPDATE_CHECK", localProp("updateCheck", "true"))
    }

    // The debug keystore Android generates is per-machine, so an APK signed
    // with CI's would not install over one signed on a developer's laptop.
    // A stable key is what makes an automated release upgradeable at all.
    // Absent the env vars (any local build) this config is simply unused.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // A debug build installs next to the release one ("Wraith Dev"), so
        // trying a branch on a phone never replaces, or logs out of, the app
        // in daily use. It never offers the release as an update either.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        release {
            // R8: shrinks the dex from ~26 MB. No reflection or by-name
            // lookups in this app; OkHttp and AndroidX ship their own rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // AGP otherwise embeds an encrypted dependency list in the APK Signing
    // Block that only Google can read. F-Droid rejects it, and release.yml
    // fails the build if it ever reappears.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
}

dependencies {
    // Same matched set as wraith-bus-ingestor-apk: one step behind the
    // release wave that bumped minimum compileSdk to 37 (still a preview).
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")

    // Compose libraries pinned directly, not via a BOM. No
    // material-icons-extended: with minify off it adds megabytes for the
    // handful of icons this app draws; material3 already brings icons-core.
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-graphics:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.runtime:runtime:1.11.4")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    // OkHttp and org.json (bundled in the platform), no Retrofit/Moshi -- a
    // few hand-shaped endpoints do not need a serialization framework.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Markdown -> AnnotatedString for chat replies. Pure Java, no Android
    // dependency, no transitive deps.
    implementation("org.commonmark:commonmark:0.24.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Android's own org.json is a stub that throws in JVM unit tests; the
    // real artifact puts working parsing on the test classpath.
    testImplementation("org.json:json:20240303")
}
