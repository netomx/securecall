# Tink references optional classes that are not shipped on Android
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# WebRTC: the native library looks up Java classes, methods and fields by name (JNI)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
