# Native engine packages use reflection and extract bundled resources by name.
-keep class com.yausername.** { *; }
# Commons Compress registers ZIP fields with reflective no-argument constructors during first extraction.
-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Media3 ships its own consumer rules; avoid retaining its unused renderers and protocols.
