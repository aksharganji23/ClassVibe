# ClassVibe R8 rules. Room, WorkManager, ML Kit and DataStore ship their own consumer rules.

# Keep worker / receiver class names (instantiated by the system / WorkManager by name).
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends android.content.BroadcastReceiver { <init>(); }
-keep class * extends android.appwidget.AppWidgetProvider { <init>(); }

# Enums looked up by name from stored strings.
-keepclassmembers enum com.classvibe.app.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ZXing
-dontwarn com.google.zxing.**
