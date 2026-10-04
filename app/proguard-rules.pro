-keepclasseswithmembernames class com.miunlock.sniper.core.Native {
    native <methods>;
}

# JNI ищет эти методы через GetMethodID по имени и сигнатуре
-keep interface com.miunlock.sniper.core.Native$AttemptCallback { *; }

-keepclassmembers class * implements com.miunlock.sniper.core.Native$AttemptCallback {
    void onPhase(int, long);
    int performAttempt(int, long, long);
}

# OkHttp / Okio / Kotlin (minify в release)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlin.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
