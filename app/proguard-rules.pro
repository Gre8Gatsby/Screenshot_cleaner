# Aggressive R8 rules for minimum APK size

# 1. Keep JNI Native Methods and Bridge Class
-keep class com.expirer.core.ExpirerJni {
    native <methods>;
    *;
}

# 2. Keep Data classes serialized across JNI
-keepclassmembers class com.expirer.core.ClassificationResult { *; }
-keepclassmembers class com.expirer.core.ScreenshotRecord { *; }
-keepclassmembers class com.expirer.core.ExpirationSummary { *; }
-keepclassmembers class com.expirer.core.DBStats { *; }

# 3. Keep WorkManager Worker constructor
-keep public class com.expirer.worker.ExpireWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 4. Strip unused logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# 5. Optimize and shrink
-repackageclasses 'com.expirer.s'
-allowaccessmodification

