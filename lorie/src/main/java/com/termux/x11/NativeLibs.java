package com.termux.x11;

import java.io.File;
import java.net.URL;

/**
 * Loads one of XDesk's native libraries in a plain app_process (the X server, the shell's input service), whose class
 * loader only has the APK. XDesk's libraries are extracted at install (the built-in Linux runs Burrow from the
 * native library directory, so they are compressed in the APK): the extracted copy next to the APK is the one to load,
 * readable by Termux and the shell too. An APK that keeps them stored uncompressed loads them from the APK itself.
 */
public final class NativeLibs {
    private NativeLibs() { }

    /** True when it was found and loaded. */
    public static boolean load(ClassLoader loader, String name) {
        boolean is64 = android.os.Process.is64Bit();
        String abi = is64 ? android.os.Build.SUPPORTED_64_BIT_ABIS[0] : android.os.Build.SUPPORTED_32_BIT_ABIS[0];
        String file = "lib" + name + ".so";
        URL res = loader != null ? loader.getResource("lib/" + abi + "/" + file) : null;
        if (res == null) return false;
        String inApk = res.getFile().replace("file:", "");
        int bang = inApk.indexOf('!');
        if (bang > 0) {
            File apkDir = new File(inApk.substring(0, bang)).getParentFile();
            File extracted = new File(apkDir, "lib/" + abiDir(abi) + "/" + file);
            if (apkDir != null && extracted.isFile()) {
                System.load(extracted.getPath());
                return true;
            }
        }
        System.load(inApk);
        return true;
    }

    /** The directory name Android extracts an ABI's libraries to. */
    private static String abiDir(String abi) {
        switch (abi) {
            case "arm64-v8a": return "arm64";
            case "armeabi-v7a": case "armeabi": return "arm";
            default: return abi;
        }
    }
}
