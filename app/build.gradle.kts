plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.ambilight"
    compileSdk = 37
    defaultConfig {
        applicationId = "local.ambilight"
        minSdk = 31
        // 34: a connectedDevice foreground service may start from BOOT_COMPLETED without extra rules.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            // PixelDaemon is started by name through app_process, so keep every class as written.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.mobile:dadb:2.0.0")
    testImplementation("junit:junit:4.13.2")
}
