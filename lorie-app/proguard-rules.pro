-dontobfuscate

# Started by name inside Shizuku's process, nothing in the app calls its constructor.
-keep class com.termux.x11.InjectorService { <init>(...); }
-keep class com.termux.x11.Evdev { native <methods>; }
