package com.termux.x11;

/**
 * Raw input devices. Only usable as the shell user, which is in the input group.
 * ioctls need real arguments (EVIOCGRAB takes its value directly), which is why this is native rather than Java.
 */
final class Evdev {
    static final int CAP_MOUSE = 1, CAP_KEYBOARD = 2;
    static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2;
    /** type in the poll result when the device is gone. */
    static final int GONE = -1, DEVICES_CHANGED = -2;
    /** Values per event in the poll result: device index, type, code, value. */
    static final int STRIDE = 4;

    static {
        try {
            System.loadLibrary("xdesk_evdev");
        } catch (UnsatisfiedLinkError e) {
            // Plain app_process: its class loader only has the APK (NativeLibs finds the extracted library)
            if (!NativeLibs.load(Evdev.class.getClassLoader(), "xdesk_evdev")) throw e;
        }
    }

    private Evdev() { }

    /** Returns a file descriptor, or a negative errno. */
    static native int open(String path);
    static native void close(int fd);
    /** CAP_ bits, 0 for anything that is not an external mouse or keyboard. */
    static native int capabilities(int fd);
    private static native byte[] nameBytes(int fd);
    static String name(int fd) {
        return new String(nameBytes(fd), java.nio.charset.StandardCharsets.UTF_8);
    }
    static native int[] pressedKeys(int fd);
    /** Exclusive access. Ends by itself when the process exits, so a crash cannot leave the user without a keyboard. */
    static native boolean grabIdleKeyboard(int fd);
    static native int grab(int fd, boolean on);
    /** Waits for events and returns how many were put in out, or a negative errno. */
    static native int watchDevices();
    static native int poll(int[] fds, int count, int[] out, int timeoutMs, int watchFd);
}
