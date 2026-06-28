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
        versionCode = 11
        versionName = "1.10"
    }

    sourceSets {
        getByName("main") {
            setRoot("app/src/main")
        }
        getByName("test") {
            setRoot("../FlightAlert-test-artifacts/test")
        }
        getByName("androidTest") {
            setRoot("../FlightAlert-test-artifacts/androidTest")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    //noinspection UseTomlInstead
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar")
}
