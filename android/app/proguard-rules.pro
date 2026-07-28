# kotlinx.serialization uses reflection on its generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# PDFBox-Android ships optional AWT/font code paths that Android never loads.
-dontwarn org.apache.pdfbox.**
-dontwarn com.tom_roush.harmony.awt.**
-dontwarn javax.imageio.**

# OkHttp / Okio optional platform hooks.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup keeps no reflective entry points, but its optional SAX bits warn.
-dontwarn org.jsoup.**
