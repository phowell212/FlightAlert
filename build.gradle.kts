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
        versionCode = 7
        versionName = "1.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            setRoot("app/src/main")
        }
        getByName("test") {
            setRoot("app/src/test")
        }
        getByName("androidTest") {
            setRoot("app/src/androidTest")
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
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0-alpha05")
}
