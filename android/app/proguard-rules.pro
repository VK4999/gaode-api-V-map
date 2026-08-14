# ================================================================
# ProGuard 混淆规则 — gaode地图
# ================================================================

# 保持 Capacitor 相关类不被混淆
-keep class com.getcapacitor.** { *; }
-dontwarn com.getcapacitor.**

# 保持后端服务相关类
-keep class com.gaode.map.BackendService { *; }
-keep class com.gaode.map.MainActivity { *; }

# WebView 相关
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn android.webkit.**

# 保持 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}
