plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.zys.worldwindjni"
    compileSdk {
        version = release(37) // 与 app 及 earth.worldwind:worldwind:2.0.9 保持一致
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // worldwindjni 为 WorldWind 渲染内核下沉 JNI 的独立模块，仅打包与 app 一致的 ABI
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "28.2.13676358"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
