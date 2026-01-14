# ProGuard rules for FitnessMirrorTV

# Keep WebRTC classes
-keep class org.webrtc.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep YouTube Player
-keep class com.pierfrancescosoffritti.** { *; }
