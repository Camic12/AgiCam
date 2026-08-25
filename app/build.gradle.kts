plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.epm.camaraterreno"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.epm.camaraterreno"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX
    val camerax_version = "1.3.0"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-video:${camerax_version}")

    // Google Play Services para Ubicación (GPS)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.0")

    // Glide y ViewPager2 para la Galería
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // PhotoView: Para el Zoom suave e interactivo
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
}