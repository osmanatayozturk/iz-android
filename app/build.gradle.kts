import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val osmClientId = providers.environmentVariable("OSM_CLIENT_ID").orNull
    ?: local.getProperty("OSM_CLIENT_ID", "")

val groupSupabaseUrl = providers.environmentVariable("GROUP_SUPABASE_URL").orNull
    ?: local.getProperty("GROUP_SUPABASE_URL", "")
val groupSupabaseKey = providers.environmentVariable("GROUP_SUPABASE_KEY").orNull
    ?: local.getProperty("GROUP_SUPABASE_KEY", "")
// Only public client credentials can be packaged. Administrative keys stay on the server.
if (groupSupabaseKey.isNotBlank()) {
    val anonymousLegacyKey = runCatching {
        val body = String(Base64.getUrlDecoder().decode(groupSupabaseKey.split('.')[1]))
        Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(body)
    }.getOrDefault(false)
    require(groupSupabaseKey.startsWith("sb_publishable_") || anonymousLegacyKey) {
        "GROUP_SUPABASE_KEY must be a public publishable or anon key; secret/service keys cannot be packaged."
    }
}
fun groupBuildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
android {
    namespace = "com.atay.iz"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.atay.iz"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GROUP_SUPABASE_URL", groupBuildString(groupSupabaseUrl))
        buildConfigField("String", "GROUP_SUPABASE_KEY", groupBuildString(groupSupabaseKey))
        manifestPlaceholders["appAuthRedirectScheme"] = "com.atay.iz"
        buildConfigField("String", "OSM_CLIENT_ID", "\"${osmClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.car.app:app-testing:1.7.0") {
        // Instrumentation uses real Android; Robolectric's service loaders replace
        // ActivityScenario/Espresso hooks and cannot run inside ART.
        exclude(group = "org.robolectric")
    }
    implementation(project(":wear-protocol"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
// Room exports a new schema during KSP. Package it only after that generation finishes,
// including on the first build after a database version change.
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn("kspDebugKotlin")
}
