plugins { id("com.android.application") }
android {
    namespace = "com.example.island"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.island"
        minSdk = 30; targetSdk = 34
        versionCode = 1; versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { compileOnly("de.robv.android.xposed:api:82") }
