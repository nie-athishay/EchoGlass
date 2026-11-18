-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class com.echoglass.music.data.db.** { *; }
-keep,includedescriptorclasses class com.echoglass.music.data.backup.** { *; }
