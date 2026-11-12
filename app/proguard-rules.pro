# Native engine packages use reflection and extract bundled resources by name.
-keep class com.yausername.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Media3 ships its own consumer rules; avoid retaining its unused renderers and protocols.
