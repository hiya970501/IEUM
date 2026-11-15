plugins { alias(libs.plugins.android.application) }

android {
    sourceSets.named("main") { kotlin.srcDir(rootProject.file("shared/src/main/kotlin")) }
    namespace = "com.example.afterglow.phone"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.afterglow.phone"
        minSdk = 28
        targetSdk = 34
        versionCode = 14
        versionName = "0.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    constraints {
        implementation("com.google.guava:guava:33.8.0-android") {
            version { strictly("33.8.0-android") }
            because("Replace Health Connect's Guava 31.1 transitive runtime: GHSA-5mg8-w23w-74h3, GHSA-7g45-4rm6-3mm3, GHSA-xxph-c9ww-hj94. Android minSdk 24 fits this app's 28.")
        }
    }
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
