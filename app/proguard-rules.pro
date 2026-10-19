-keep class com.artifex.mupdf.fitz.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Smaller dex: move all classes into one package and let R8 widen access where it helps.
-repackageclasses ''
-allowaccessmodification

# Debug logging does nothing in the release build, so let R8 delete those calls (and the strings built for them).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
