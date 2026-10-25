# WebRTC is called from native code via JNI; R8 must not rename or strip it.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
