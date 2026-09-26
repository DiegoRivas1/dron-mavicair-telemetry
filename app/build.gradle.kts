plugins {
    id("com.android.application")
    //id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.diegorivas.drone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.diegorivas.drone"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        multiDexEnabled = true

        // Requerido por dji-sdk (usa clases nativas .so por arquitectura)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    //kotlinOptions {
        //jvmTarget = "11"
    //}

    // Necesario para las clases legacy de Apache HTTP que usa dji-sdk 4.18
    useLibrary("org.apache.http.legacy")

    packaging {
        resources {
            pickFirsts += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/rxjava.properties"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // DJI Mobile SDK 4.18
    implementation("com.dji:dji-sdk:4.18")
    compileOnly("com.dji:dji-sdk-provided:4.18")

    // Clases Apache HTTP legacy requeridas por dji-sdk 4.18
    implementation("org.apache.httpcomponents:httpcore:4.4.16")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
