plugins {
    id("com.android.application")
}

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
        release {
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // 감지 엔진(순수 Kotlin) JVM 단위 테스트용. 표준 JUnit4 외 추가 테스트 라이브러리는 쓰지 않는다.
    testImplementation("junit:junit:4.13.2")
}
