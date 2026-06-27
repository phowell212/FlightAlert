plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.flightalert"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.flightalert"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "1.8"
    }

    sourceSets {
        getByName("main") {
            setRoot("app/src/main")
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
    implementation("com.github.luben:zstd-jni:1.5.7-8@aar")
}
