-keepclasseswithmembernames class com.miunlock.sniper.core.Native {
    native <methods>;
}

# JNI ищет эти методы через GetMethodID по имени и сигнатуре
-keep interface com.miunlock.sniper.core.Native$AttemptCallback { *; }

-keepclassmembers class * implements com.miunlock.sniper.core.Native$AttemptCallback {
    void onPhase(int, long);
    int performAttempt(int, long, long);
}
