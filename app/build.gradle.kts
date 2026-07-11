plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "be.mv3d.tablet"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.mv3d.tablet"
        minSdk = 26
        targetSdk = 34
        // CI zet -PappVersionCode=<run_number> zodat elke build een hoger nummer krijgt (auto-update)
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1"
    }

    // Eén vaste keystore voor alle builds → dezelfde signatuur → auto-update werkt.
    signingConfigs {
        create("shared") {
            storeFile = file("mv3d-signing.p12")
            storePassword = "mv3dtablet"
            keyAlias = "mv3dkey"
            keyPassword = "mv3dtablet"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // cloudflared wordt als native lib meegeleverd (jniLibs/arm64-v8a/libcloudflared.so) zodat het
    // op schijf in de uitvoerbare nativeLibraryDir belandt (Android 10+ verbiedt exec vanuit datamap).
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1") // ingebedde noVNC-http + ws-brug
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR scannen van de koppelcode
    // org.json zit al in het Android-framework (geen aparte dep nodig)
}
