import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 키는 저장소에 넣지 않는 local.properties 에서만 읽는다. 값을 출력하지 않는다.
val anthropicApiKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("ANTHROPIC_API_KEY").orEmpty().trim()

android {
    namespace = "com.example.shortsdetector"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.shortsdetector"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        }
        release {
            // APK 안의 키는 꺼낼 수 있으므로 릴리스에는 넣지 않는다. 비어 있으면 AI 기능이 꺼진다.
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.12.2")

    // 정식 화면. 내장 Kotlin 2.2 가 읽을 수 있는 버전으로 고정한다. 차트는 Canvas 로 그린다.
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // 감지 엔진(순수 Kotlin) JVM 단위 테스트용.
    testImplementation("junit:junit:4.13.2")
    // 앱은 플랫폼 내장 org.json 을 쓴다. JVM 테스트의 android.jar 에서는 스텁이라 실제 구현이 필요하다. 앱에는 포함되지 않는다.
    testImplementation("org.json:json:20240303")
}
