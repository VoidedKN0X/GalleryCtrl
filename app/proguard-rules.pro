# kotlinx.serialization models (library ships its own rules; these keep saved history and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.galleryctrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.galleryctrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.galleryctrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google-free: Guava's listenablefuture jar is excluded from the build (see app/build.gradle.kts).
# AndroidX's concurrent-futures still names its interface in classes GalleryCtrl never uses.
-dontwarn com.google.common.util.concurrent.ListenableFuture

# Release builds drop verbose, debug and info logging (from the app and its libraries), so nothing from
# them can end up in the phone's log. Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Photo map: Mapsforge reads its render theme with XmlPullParser and creates its instructions by name, and draws
# theme symbols with AndroidSVG; keep both whole.
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
