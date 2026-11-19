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
