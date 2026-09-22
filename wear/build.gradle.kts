plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.wristcontrol.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wristcontrol.claude"
        // Wear OS 4 == API 33. The tile + protolayout stack and the phone
        // remote-auth flow both assume this baseline.
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Overridable from ~/.gradle/gradle.properties or -P flags so the app
        // can be pointed at a staging relay without touching source.
        val apiBase = providers.gradleProperty("wristcontrol.apiBaseUrl")
            .getOrElse("https://api.anthropic.com/")
        val wsBase = providers.gradleProperty("wristcontrol.wsBaseUrl")
            .getOrElse("wss://api.anthropic.com/")
        val oauthClientId = providers.gradleProperty("wristcontrol.oauthClientId")
            .getOrElse("wristcontrol-wear")
        val oauthAuthorizeUrl = providers.gradleProperty("wristcontrol.oauthAuthorizeUrl")
            .getOrElse("https://claude.ai/oauth/authorize")
        val oauthTokenUrl = providers.gradleProperty("wristcontrol.oauthTokenUrl")
            .getOrElse("https://console.anthropic.com/v1/oauth/token")

        buildConfigField("String", "API_BASE_URL", "\"" + apiBase + "\"")
        buildConfigField("String", "WS_BASE_URL", "\"" + wsBase + "\"")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"" + oauthClientId + "\"")
        buildConfigField("String", "OAUTH_AUTHORIZE_URL", "\"" + oauthAuthorizeUrl + "\"")
        buildConfigField("String", "OAUTH_TOKEN_URL", "\"" + oauthTokenUrl + "\"")
    }

    buildTypes {
        debug {
            // Phase 2 of the design doc: the whole UI, including tiles, can be
            // driven from an in-memory fake with no account and no network.
            buildConfigField("boolean", "ALLOW_DEMO_MODE", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_DEMO_MODE", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Codec and repository tests are pure JVM apart from android.util.Log.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.phone.interactions)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.protolayout.material)
    implementation(libs.androidx.protolayout.expression)
    debugImplementation(libs.androidx.wear.tiles.tooling)
    implementation(libs.androidx.wear.tiles.tooling.preview)

    implementation(libs.play.services.wearable)
    implementation(libs.okhttp)
    implementation(libs.guava)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
