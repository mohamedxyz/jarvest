# JNA and the generated UniFFI code find classes, fields, and methods by name at
# runtime, so shrinking must leave them exactly as they are.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class dev.goaltracker.core.** { *; }
-dontwarn java.awt.**
