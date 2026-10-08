import java.util.Properties

// The one place this app's version is written down. commitizen rewrites the
// appVersionName line on bump (.cz.yaml version_files points at it), and the
// android-X.Y.Z tag is cut FROM this value, never invented alongside it.
//
// versionCode is DERIVED, never stored, so it cannot be forgotten on a bump:
//
//     1.0.3  ->  10003
//
// Monotonic, which is what F-Droid requires. minor and patch must stay under
// 100 or 1.100.0 would collide with 2.0.0 -- the require() makes that a build
// failure instead of a silent downgrade.
// 0.0.0 is a placeholder: there are no tags yet, so commitizen reads the current
// version as 0.0.0 and rewrites this line to the first real one on bump.
val appVersionName = "0.1.0"
val appVersionCode = appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "versionCode scheme needs minor and patch < 100: $appVersionName" }
    (major * 10000 + minor * 100 + patch)
        .coerceAtLeast(1) // the 0.0.0 placeholder must still be a legal versionCode
}

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
        // anyone running their own Odysseus server. No abiFilters -- there is
        // no native code, so the APK is universal for free.
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

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
        release {
            isMinifyEnabled = false
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
    // Android's own org.json is a stub that throws in JVM unit tests; the
    // real artifact puts working parsing on the test classpath.
    testImplementation("org.json:json:20240303")
}
