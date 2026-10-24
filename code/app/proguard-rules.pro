# 混淆规则（MVP 先关 minify，此文件仅作占位备用）
# kotlinx-serialization：保留序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.englishcoach.** {
    *** Companion;
}
-keepclasseswithmembers class com.englishcoach.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
