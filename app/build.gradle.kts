plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.verisonder.sondershield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.verisonder.sondershield"
        // Same floor as SonderEye and SonderFlow.
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.1"
    }

    signingConfigs {
        create("release") {
            // Supplied by CI from repository secrets. Absent locally, so the release build
            // falls back to the debug key. Values are trimmed: a pasted secret picks up
            // invisible whitespace and fails as a wrong alias.
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")?.trim()
            if (!storeFilePath.isNullOrEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")?.trim()
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")?.trim()
                // PKCS12: the key password always equals the store password.
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.trim()
                    ?: System.getenv("RELEASE_STORE_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("RELEASE_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    packaging {
        // The engine ships arm64 and arm only; Go's libs are already stripped.
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

// The Go engine, built by tools/build_engine.sh with gomobile. Fail early with a clear
// message instead of a page of unresolved "shield.*" symbols.
val engineAar = file("libs/engine.aar")
tasks.named("preBuild") {
    doFirst {
        if (!engineAar.exists()) {
            throw GradleException("app/libs/engine.aar is missing. Run tools/build_engine.sh (needs Go and the NDK).")
        }
    }
}

dependencies {
    implementation(files("libs/engine.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
