# Shared runtime and dependency rules are supplied by the stock common AAR.
-keep,allowoptimization,allowshrinking class com.microsoft.identity.common.edgewebview.** { *; }
-keepclassmembers class com.microsoft.identity.common.edgewebview.** {
    @android.webkit.JavascriptInterface <methods>;
}
