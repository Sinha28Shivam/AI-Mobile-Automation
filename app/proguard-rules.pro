# Vosk uses JNA, which reflects over its own classes and over native-mapped interfaces,
# structures and callbacks. Keep them intact for R8.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class org.vosk.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# JNA references desktop-only classes that do not exist on Android.
-dontwarn java.awt.**
-dontwarn com.sun.jna.**