-keep class com.artifex.mupdf.fitz.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Smaller dex: move all classes into one package and let R8 widen access where it helps.
-repackageclasses ''
-allowaccessmodification
