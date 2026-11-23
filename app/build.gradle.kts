plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.drivepal"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.drivepal"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        // The public Overpass server is sometimes overloaded. Override with
        // -Pdrivepal.overpassUrl=<endpoint> (or in ~/.gradle/gradle.properties) to use another one.
        val overpassUrl = providers.gradleProperty("drivepal.overpassUrl")
            .orElse("https://overpass-api.de/api/interpreter").get()
        buildConfigField("String", "OVERPASS_URL", "\"$overpassUrl\"")

        // Wikimedia and OpenStreetMap ask API clients to give a contact in the User-Agent. Set one with
        // -Pdrivepal.contact=<project URL> (or in ~/.gradle/gradle.properties). Prefer a URL: an email address
        // would identify you in every request. Nothing is built in. Keep it plain ASCII.
        val contact = providers.gradleProperty("drivepal.contact").orElse("").get()
        buildConfigField("String", "CONTACT", "\"$contact\"")

        // The AI that writes the Stories: any OpenAI-compatible server. The default is Ollama on the development
        // computer, as seen from the Android emulator. Override with -Pdrivepal.aiUrl=, -Pdrivepal.aiModel=,
        // -Pdrivepal.aiKey= and -Pdrivepal.aiReasoningEffort= (empty = leave the field out, for providers that
        // reject it). Put the key in ~/.gradle/gradle.properties, never in this repository. It ends up inside the
        // APK, which is fine for a personal build and not for a published one.
        fun setting(name: String, default: String) = providers.gradleProperty(name).orElse(default).get()
        buildConfigField("String", "AI_BASE_URL", "\"${setting("drivepal.aiUrl", "http://10.0.2.2:11434/v1")}\"")
        buildConfigField("String", "AI_MODEL", "\"${setting("drivepal.aiModel", "gemma4:e4b")}\"")
        buildConfigField("String", "AI_API_KEY", "\"${setting("drivepal.aiKey", "")}\"")
        buildConfigField("String", "AI_REASONING_EFFORT", "\"${setting("drivepal.aiReasoningEffort", "none")}\"")

        // How long the silence must last before an Interlude (a Story about an untold part of the Province or
        // Country) is told. 300 s is the real value; shorten it with -Pdrivepal.interludeAfterSeconds=60 to try it.
        buildConfigField("int", "INTERLUDE_AFTER_SECONDS", setting("drivepal.interludeAfterSeconds", "300"))
    }

    // Release signing: the key and its passwords live outside this repository, in ~/.gradle/gradle.properties
    // (drivepal.releaseStoreFile, drivepal.releaseStorePassword, drivepal.releaseKeyAlias, drivepal.releaseKeyPassword).
    // Without them a release build still builds, unsigned. Losing the key means the installed app cannot be updated:
    // back up the key file and those properties together.
    val releaseStoreFile = providers.gradleProperty("drivepal.releaseStoreFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("drivepal.releaseStorePassword").get()
                keyAlias = providers.gradleProperty("drivepal.releaseKeyAlias").get()
                keyPassword = providers.gradleProperty("drivepal.releaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        // Debug builds are a separate app (app.drivepal.debug, "DrivePal debug"), so they install next to a release
        // build, each with its own data and its own packs folder (Android/data/<app id>/files/packs/).
        debug {
            applicationIdSuffix = ".debug" // its name, "DrivePal debug", is in src/debug/res
        }
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
