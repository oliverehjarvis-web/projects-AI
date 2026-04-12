# LiteRT-LM (on-device inference)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep native method names (LiteRT uses JNI)
-keepclassmembers class * {
    native <methods>;
}
