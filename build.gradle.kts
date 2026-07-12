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

val externalFlightAlertTestRoot = providers
    .gradleProperty("flightAlertExternalTestRoot")
    .map { file(it) }
    .getOrElse(rootProject.layout.projectDirectory.dir("../FlightAlert-test-artifacts").asFile)

val externalFlightAlertTestApplicationId = providers
    .gradleProperty("flightAlertExternalTestApplicationId")
    .orNull
    ?.also { applicationId ->
        require(externalFlightAlertTestsEnabled) {
            "flightAlertExternalTestApplicationId requires flightAlertExternalTests=true"
        }
        require(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(applicationId)) {
            "flightAlertExternalTestApplicationId must be a lowercase Android application ID"
        }
        require(applicationId != "com.flightalert") {
            "flightAlertExternalTestApplicationId must differ from the production application ID"
        }
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalFlightAlertTestApplicationId?.let { testApplicationId = it }
    }

    sourceSets {
        getByName("main") {
            setRoot("app/src/main")
        }
        if (externalFlightAlertTestsEnabled) {
            getByName("test") {
                setRoot(File(externalFlightAlertTestRoot, "test").path)
            }
            getByName("androidTest") {
                setRoot(File(externalFlightAlertTestRoot, "androidTest").path)
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
        testImplementation("org.json:json:20260522")
    }
    if (externalFlightAlertTestsEnabled) {
        androidTestImplementation("androidx.test:runner:1.7.0")
        androidTestImplementation("androidx.test.ext:junit:1.3.0")
        androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    }
}
