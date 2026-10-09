@file:Suppress("UnstableApiUsage", "UNNECESSARY_SAFE_CALL")

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.flightalert"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.flightalert"
        minSdk = 29
        //noinspection AndroidLintEditedTargetSdkVersion
        targetSdk = 37
        versionCode = 13
        versionName = "1.12"
        manifestPlaceholders["launcherActivity"] = "com.flightalert.fundamentals.FundamentalsActivity"
        manifestPlaceholders["applicationLabel"] = "@string/app_name"
        manifestPlaceholders["fullAppServicesEnabled"] = "true"
        manifestPlaceholders["shellProfileable"] = "false"
    }

    sourceSets {
        getByName("main") {
            setRoot("app/src/main")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
        create("fundamentals") {
            initWith(getByName("release"))
            applicationIdSuffix = ".fundamentals"
            versionNameSuffix = "-fundamentals"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            manifestPlaceholders["launcherActivity"] = "com.flightalert.fundamentals.FundamentalsActivity"
            manifestPlaceholders["applicationLabel"] = "Flight Alert Fundamentals"
            manifestPlaceholders["fullAppServicesEnabled"] = "true"
            manifestPlaceholders["shellProfileable"] = "true"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    //noinspection NewerVersionAvailable,UseTomlInstead
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar")
}
