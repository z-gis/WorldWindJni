plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

// worldwindjni 库版本与 Maven 坐标：与 GitHub Release tag（v1.1.0）保持一致
// 1.1.0：新增 HarmonyOS NEXT 双平台支持（引擎共享 + NAPI/EGL/ArkTS 鸿蒙侧），Android 功能零变化
group = "com.zys"
version = "1.1.0"

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
    // 发布 release 变体的 AAR 供 maven-publish 消费（自动生成 sources jar 与依赖元数据）
    publishing {
        singleVariant("release") {
            withSourcesJar()
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

// Maven 发布：坐标 com.zys:worldwindjni:<version>。
// 执行 `gradlew :worldwindjni:publish` 会先 assembleRelease 再将 maven 布局输出到 worldwindjni/build/repo，
// 由 .github/workflows/publish-maven.yml 部署到 gh-pages 的 /maven 目录供消费者按坐标自动下载。
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.zys"
                artifactId = "worldwindjni"
                version = project.version.toString()
                pom {
                    name.set("worldwindjni")
                    description.set("WorldWind 渲染内核下沉 JNI 的 Android 地图渲染库（静态链接 GDAL/PROJ/libcurl 等）")
                    url.set("https://github.com/z-gis/WorldWindJni")
                    scm {
                        connection.set("https://github.com/z-gis/WorldWindJni.git")
                        url.set("https://github.com/z-gis/WorldWindJni")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "buildRepo"
                url = uri(layout.buildDirectory.dir("repo"))
            }
        }
    }
}
