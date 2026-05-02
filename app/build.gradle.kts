plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xposed.douyinhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xposed.douyinhelper"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "1.2.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            res.srcDirs("src/main/res")
        }
    }
}

dependencies {
    // Xposed API - compileOnly, provided at runtime by LSPosed
    compileOnly(libs.xposed.api)

    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core)
}
