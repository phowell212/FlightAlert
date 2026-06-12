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
        versionCode = 5
        versionName = "1.4"
    }

    sourceSets {
        getByName("main") {
            setRoot("app/src/main")
        }
        getByName("test") {
            setRoot("app/src/test")
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
    testImplementation(libs.junit)
}
