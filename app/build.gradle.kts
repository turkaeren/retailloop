plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val gdProps = java.util.Properties().apply {
    val f = file("gdrive.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun gdv(k: String): String = (System.getenv("GDRIVE_$k") ?: gdProps.getProperty(k) ?: "").trim()

android {
    namespace = "com.retail.loop"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.retail.loop"; minSdk = 24; targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
        buildConfigField("String", "GD_CLIENT_ID", "\"" + gdv("CLIENT_ID") + "\"")
        buildConfigField("String", "GD_CLIENT_SECRET", "\"" + gdv("CLIENT_SECRET") + "\"")
        buildConfigField("String", "GD_REFRESH_TOKEN", "\"" + gdv("REFRESH_TOKEN") + "\"")
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("retailloop.keystore"); storePassword = "retailloop"
            keyAlias = "retailloop"; keyPassword = "retailloop"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
