-dontobfuscate

# Started by name inside Shizuku's process, nothing in the app calls its constructor.
-keep class com.termux.x11.InjectorService { <init>(...); }
-keep class com.termux.x11.Evdev { native <methods>; }

# Started by name through app_process over wireless debugging.
-keep class com.termux.x11.ShellServer { public static void main(java.lang.String[]); }
# libspake2 finds this class and its native methods by name, and reads its fields.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
# HiddenApiBypass reads the layout of these mirror classes by reflection.
-keep class org.lsposed.hiddenapibypass.** { *; }
# libadb looks these up by name when they exist. XDesk has only the platform's Conscrypt.
-dontwarn org.conscrypt.**
