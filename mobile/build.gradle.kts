plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.wristcontrol.phone"
    compileSdk = 35

    defaultConfig {
        // Must match the wear applicationId so Play pairs the two APKs and the
        // Data Layer delivers messages between them.
        applicationId = "dev.wristcontrol.claude"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        val oauthClientId = providers.gradleProperty("wristcontrol.oauthClientId")
            .getOrElse("wristcontrol-wear")
        val oauthAuthorizeUrl = providers.gradleProperty("wristcontrol.oauthAuthorizeUrl")
            .getOrElse("https://claude.ai/oauth/authorize")
        val oauthTokenUrl = providers.gradleProperty("wristcontrol.oauthTokenUrl")
            .getOrElse("https://console.anthropic.com/v1/oauth/token")

        buildConfigField("String", "OAUTH_CLIENT_ID", "\"" + oauthClientId + "\"")
        buildConfigField("String", "OAUTH_AUTHORIZE_URL", "\"" + oauthAuthorizeUrl + "\"")
        buildConfigField("String", "OAUTH_TOKEN_URL", "\"" + oauthTokenUrl + "\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
