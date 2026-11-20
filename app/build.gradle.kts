import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nirkzxc.nfcreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nirkzxc.nfcreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Auth token for TCP connections. Provide via -PnfcAuthToken=... or the
        // NFC_AUTH_TOKEN environment variable. No default is shipped: an unset or
        // blank token means no client can authenticate (secure by default).
        val authToken =
            rootProject.findProperty("nfcAuthToken") ?: System.getenv("NFC_AUTH_TOKEN") ?: ""
        buildConfigField("String", "AUTH_TOKEN", "\"$authToken\"")
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) {
                val input = propsFile.inputStream()
                try {
                    props.load(input)
                } finally {
                    input.close()
                }
            }
            val storeFileProp = props.getProperty("storeFile")
            storeFile = if (storeFileProp != null) rootProject.file(storeFileProp) else null
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Release builds require an explicit release signing configuration.
            // Provide signing.properties (git-ignored) or CI equivalents.
            // Debug signing is never used for release.
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}