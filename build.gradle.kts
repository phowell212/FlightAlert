@file:Suppress("UnstableApiUsage", "UNNECESSARY_SAFE_CALL")

plugins {
    alias(libs.plugins.android.application)
}

val externalFlightAlertTestsEnabled = providers.gradleProperty("flightAlertExternalTests")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val repositoryFlightAlertTestsEnabled = providers.gradleProperty("flightAlertRepositoryTests")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

if (externalFlightAlertTestsEnabled && repositoryFlightAlertTestsEnabled) {
    throw GradleException(
        "flightAlertExternalTests and flightAlertRepositoryTests cannot both be enabled",
    )
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
        if (externalFlightAlertTestsEnabled) {
            getByName("test") {
                setRoot("../FlightAlert-test-artifacts/test")
            }
            getByName("androidTest") {
                setRoot("../FlightAlert-test-artifacts/androidTest")
            }
        } else if (repositoryFlightAlertTestsEnabled) {
            getByName("test") {
                setRoot("tools/experiment8/kotlin-test")
            }
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

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enableAndroidTest = externalFlightAlertTestsEnabled
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable =
            externalFlightAlertTestsEnabled || repositoryFlightAlertTestsEnabled
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    //noinspection UseTomlInstead
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar")
    if (externalFlightAlertTestsEnabled || repositoryFlightAlertTestsEnabled) {
        testImplementation("junit:junit:4.13.2")
    }
}
