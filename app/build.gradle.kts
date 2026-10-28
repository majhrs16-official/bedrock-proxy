plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

val appVersionName = "1.5.0"

android {
    namespace = "com.bedrockproxy"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndkVersion.get()

    defaultConfig {
        applicationId = "com.bedrockproxy"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 7
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += "-DANDROID_STL=c++_shared"
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
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    val composeBom = platform(libs.composeBom)
    implementation(composeBom)
    implementation(libs.composeUi)
    implementation(libs.composeMaterial3)
    implementation(libs.composeActivity)
    implementation(libs.composeViewModel)
    implementation(libs.composeRuntime)
    implementation(libs.composeFoundation)

    implementation(libs.lifecycleViewModel)
    implementation(libs.lifecycleRuntime)

    implementation(libs.datastorePreferences)
    implementation(libs.coroutinesAndroid)

    // NetherNet: WebRTC + data channels para clientes Bedrock 26.50+.
    implementation(libs.webrtc)

    implementation(libs.coreKtx)
    implementation(libs.appcompat)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espressoCore)
    androidTestImplementation(libs.coreTesting)
    androidTestImplementation(libs.truth)
}
