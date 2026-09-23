plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kofua.adbstayawake"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kofua.adbstayawake"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources {
        merges += "META-INF/xposed/*"
        excludes += "**"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
    testImplementation(libs.junit)
}
