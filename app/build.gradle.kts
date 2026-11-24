import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release key (signer "incog"). Path and passwords come from keystore.properties, kept out of this file.
val keyProps = Properties()
val keyPropsFile = rootProject.file("keystore.properties")
if (keyPropsFile.exists()) keyPropsFile.inputStream().use { stream -> keyProps.load(stream) }

android {
    namespace = "dev.incog.choptorch"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.incog.choptorch"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "4.1"
    }

    signingConfigs {
        create("incog") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile", "keystore/incog.jks"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("incog")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Material Color Utilities (Kotlin port, MIT/Apache-2.0): builds theme palettes from a seed colour.
    implementation("com.materialkolor:material-color-utilities:6.0.0")
}
