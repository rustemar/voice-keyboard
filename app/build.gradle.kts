plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tyraen.voicekeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tyraen.voicekeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 55
        versionName = "1.9.4"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: rootProject.file("release-keystore.jks").absolutePath
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val signingKeyAlias = System.getenv("KEY_ALIAS")
            val signingKeyPassword = System.getenv("KEY_PASSWORD")

            // Empty, not just missing: CI expands absent secrets (fork PRs) to "" and the
            // keystore file itself may be missing on a clean clone or a third-party build server.
            if (!keystorePassword.isNullOrBlank() && !signingKeyAlias.isNullOrBlank() &&
                !signingKeyPassword.isNullOrBlank() && file(keystorePath).exists()
            ) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    // The dependency-metadata block AGP embeds in the signing block is meant for Play; F-Droid's
    // scanner flags it, and nobody else reads it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "VoiceKeyboard.apk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without credentials the release build stays unsigned instead of failing at
            // packaging; that is what F-Droid and a clean clone need. findByName, not getByName:
            // F-Droid's build server strips the signingConfigs block above before building, and
            // getByName on the then-missing config throws at configuration time.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // org.json is part of the Android platform but stubbed in JVM unit tests; bring the real impl
    // so we can test parked-recording (de)serialization off-device.
    testImplementation("org.json:json:20240303")
}

// StringResourcesTest reads res/values*/strings.xml directly. Declare them as a test input, or an
// edit to a translation alone leaves testDebugUnitTest UP-TO-DATE and the check never runs locally.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPropertyName("resources").withPathSensitivity(PathSensitivity.RELATIVE)
}
