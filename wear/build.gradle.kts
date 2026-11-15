plugins { alias(libs.plugins.android.application) }

android {
    sourceSets.named("main") { kotlin.srcDir(rootProject.file("shared/src/main/kotlin")) }
    namespace = "com.example.afterglow.wear"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.afterglow.wear"
        minSdk = 30
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
