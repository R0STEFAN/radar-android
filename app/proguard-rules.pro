# Add project specific ProGuard rules here.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ua.quick.radar.** { *; }
