import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenciales de firma release (NUNCA versionado ni en GitHub): viven FUERA del arbol del
// proyecto, en ~/keystores/opendmo/, para que no las borre un rsync --delete ni acaben en git.
// OpenDMO se firma con el keystore de RadioVampiros (misma identidad de firma).
// En CI (GitHub Actions) la ruta se inyecta con la variable de entorno OPENDMO_KEYSTORE.
val keystorePropsFile = file(System.getenv("OPENDMO_KEYSTORE") ?: "/home/yo/keystores/opendmo/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "ovh.adan.opendmo"
    compileSdk = 34

    defaultConfig {
        applicationId = "ovh.adan.opendmo"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.2.1"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // serie USB por OTG (CDC/ACM autodetectado, sin root) — OpenGD77
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // passphrase cifrada (Android Keystore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
