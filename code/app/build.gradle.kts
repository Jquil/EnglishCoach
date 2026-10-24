import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取 API Key 注入 BuildConfig（文件被 git 忽略，自用无泄露风险）
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localKey(name: String): String = localProperties.getProperty(name) ?: ""

android {
    namespace = "com.englishcoach"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.englishcoach"
        minSdk = 26          // 需要 java.time（LocalDate 等），API 26+ 原生可用
        targetSdk = 35
        versionCode = 24
        versionName = "11.26"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${localKey("DEEPSEEK_API_KEY")}\"")
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"${localKey("SILICONFLOW_API_KEY")}\"")
        // 智谱 GLM（对话备选供应商；open.bigmodel.cn 获取，可选）
        buildConfigField("String", "ZHIPU_API_KEY", "\"${localKey("ZHIPU_API_KEY")}\"")
        // Azure TTS 已停用（2026-10-22 切换 SiliconFlow），仅作回切预留
        buildConfigField("String", "AZURE_SPEECH_KEY", "\"${localKey("AZURE_SPEECH_KEY")}\"")
        buildConfigField("String", "AZURE_SPEECH_REGION", "\"${localKey("AZURE_SPEECH_REGION")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // 核心
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // Compose（版本统一走 BOM）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // 导航
    implementation(libs.androidx.navigation.compose)
    // 网络：Retrofit + OkHttp + kotlinx-serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // 存储
    implementation(libs.androidx.datastore.preferences)
    // 通知
    implementation(libs.androidx.work.runtime.ktx)
    // NCE 关卡插画（SVG 渲染）
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
