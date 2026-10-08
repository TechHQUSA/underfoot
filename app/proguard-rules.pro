# Open-source app: obfuscation adds nothing and would scramble the crash reports users can share.
# Shrinking and optimizing still run.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Nordic BLE loads its request classes and Health Connect its records by name/reflection paths the shrinker cannot see.
-keep class no.nordicsemi.android.ble.** { *; }
-keep class androidx.health.connect.client.** { *; }
-dontwarn no.nordicsemi.android.ble.**
