plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val speechRuntimeTemplate = configurations.create("speechRuntimeTemplate") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val speechRuntimeModel = rootProject.file("app/src/main/assets/silero_vad.ort")
val speechRuntimeWorkDir = providers.gradleProperty("speechRuntimeWorkDir")
    .orElse(rootProject.file("external/onnxruntime").absolutePath)
    .map { rootProject.file(it).absolutePath }
val speechRuntimeOutput = rootProject.file("build/onnxruntime/onnxruntime-reduced.aar")
val speechRuntimeToolsDir = providers.gradleProperty("speechRuntimeToolsDir")

fun speechRuntimeArguments(command: String): List<String> = buildList {
    addAll(listOf("python3", rootProject.file("tools/onnxruntime/build.py").absolutePath,
        command, "--work-dir", speechRuntimeWorkDir.get(), "--model", speechRuntimeModel.absolutePath))
    speechRuntimeToolsDir.orNull?.let { addAll(listOf("--tools-dir", it)) }
}

tasks.register<Exec>("prepareSpeechRuntime") {
    group = "build setup"
    description = "Download and verify the pinned speech runtime sources and build tools."
    inputs.files(rootProject.fileTree("tools/onnxruntime") { exclude("**/__pycache__/**", "**/*.pyc") })
    inputs.file(speechRuntimeModel)
    outputs.dir(speechRuntimeWorkDir.map { file("$it/source") })
    outputs.file(speechRuntimeWorkDir.map { file("$it/prepared.json") })
    outputs.file(speechRuntimeWorkDir.map { file("$it/source-manifest.json") })
    commandLine(speechRuntimeArguments("prepare"))
}

tasks.register<Exec>("buildSpeechRuntime") {
    group = "build"
    description = "Build the four-ABI speech runtime from prepared sources without network access."
    inputs.files(rootProject.fileTree("tools/onnxruntime") { exclude("**/__pycache__/**", "**/*.pyc") })
    inputs.file(speechRuntimeModel)
    inputs.files(speechRuntimeTemplate)
    outputs.file(speechRuntimeOutput)
    outputs.file(speechRuntimeOutput.resolveSibling("${speechRuntimeOutput.name}.receipt.json"))
    doFirst {
        val sdk = android.sdkDirectory
        val ndk = providers.gradleProperty("speechRuntimeNdk").orNull
            ?: sdk.resolve("ndk/28.0.13004108").absolutePath
        commandLine(speechRuntimeArguments("build") + listOf(
            "--template-aar", speechRuntimeTemplate.singleFile.absolutePath,
            "--output", speechRuntimeOutput.absolutePath,
            "--android-sdk", sdk.absolutePath, "--android-ndk", ndk,
            "--jobs", providers.gradleProperty("speechRuntimeJobs").getOrElse("2")
        ))
    }
}

android {
    namespace = "com.tyraen.voicekeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tyraen.voicekeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 68
        versionName = "1.14.0"
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
    buildTypes.create("noiseLocal") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".noisetest"
        versionNameSuffix = "-noise-local.2"
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += listOf("release")
    }
    buildTypes.create("audioTest") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".audiotest"
        versionNameSuffix = "-audio-test.1"
        matchingFallbacks += listOf("release")
        // Installs beside the keyboard. CI uses the developer key; local builds without
        // release credentials use the debug key and cannot replace the published test APK.
        signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
            ?: signingConfigs.getByName("debug")
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
    packaging {
        // Extract the aligned ONNX libraries for 16 KB page support on this AGP version.
        jniLibs.useLegacyPackaging = true
        // The source build already strips these with the pinned NDK; retain its exact bytes.
        jniLibs.keepDebugSymbols += setOf("**/libonnxruntime.so", "**/libonnxruntime4j_jni.so")
    }
}

dependencies {
    add(speechRuntimeTemplate.name, "com.microsoft.onnxruntime:onnxruntime-android:1.24.3@aar")
    val speechRuntimeAar = providers.gradleProperty("speechRuntimeAar").orNull
    if (speechRuntimeAar != null) {
        implementation(files(rootProject.file(speechRuntimeAar)))
    } else {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    }
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
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// StringResourcesTest reads res/values*/strings.xml directly. Declare them as a test input, or an
// edit to a translation alone leaves testDebugUnitTest UP-TO-DATE and the check never runs locally.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPropertyName("resources").withPathSensitivity(PathSensitivity.RELATIVE)
}
