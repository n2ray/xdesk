package com.termux.x11;

import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import android.view.Surface;
import com.termux.x11.IInjectorListener;

/** The privileged input session. Runs as the shell user, through Shizuku or ShellServer, see InjectorService. */
interface IInjector {
    void macKeyboard(com.termux.x11.IMacKeyboard target) = 53;
    /** Called by Shizuku when the service is removed. The id is fixed by Shizuku's API. */
    void destroy() = 16777114;

    /** Returns null when ready, otherwise why injection cannot work. */
    String configure(IInjectorListener listener, int displayId, int width, int height, String packageName, float mouseSpeed) = 1;
    oneway void setDisplay(int displayId, int width, int height) = 2;
    /** Grab the physical mouse and keyboard, or give them back to the phone. */
    oneway void setForwarding(boolean on) = 3;
    oneway void setMouseSpeed(float speed) = 4;
    /** The phone screen was touched, which moves focus away from the external display. */
    oneway void phoneTouched() = 5;

    // The phone UI: touchpad, shortcut row and soft keyboard. Buttons are X numbered (1 left, 2 middle, 3 right).
    oneway void move(int dx, int dy) = 6;
    oneway void moveToFraction(float x, float y) = 37;
    oneway void button(int button, boolean down) = 7;
    oneway void scroll(int dy, int dx) = 8;
    oneway void key(int scan, boolean down) = 9;
    oneway void text(String text) = 10;
    oneway void tapKey(int scan) = 14;

    /** Returns null on success, otherwise the error. A component ending in "@<user>" starts in that user, a dual app. */
    String startApp(String component, int displayId) = 11;
    /** The window of another app presenting on this display, or null. Best effort. */
    String findPresentation(int displayId) = 12;
    /** Release everything that is held. Returns when that is done. */
    void shutdown() = 13;

    /** Another app is now on the display: release what is held, change the input method and refocus. */
    oneway void setApp(String packageName) = 15;
    oneway void setWatchSession(boolean watch) = 16;
    /** False while a Termux desktop is on the display: the hotkey must not grab anything. */
    oneway void setEnabled(boolean enabled) = 17;
    /** Left button as a finger, for this app only. Ignored for apps with their own mouse method. */
    oneway void setTouchMode(boolean on) = 20;
    /** Prefer this device for media on the whole system, type < 0 clears it. Returns null or the error. */
    String setAudioOutput(int type, String address) = 18;
    /** Connect without an app, for the sound route alone. Reads no input devices. */
    void attach(IInjectorListener listener, String ownPackage) = 19;
    /** Interface size of a display. dpi > 0 forces it, 0 always resets it, < 0 resets it if this process set it. Returns null or the error. */
    String setDisplayDensity(int displayId, int dpi) = 22;
    /**
     * Shows an external display on its whole panel, pixel for pixel, when Android has it in another shape (PanelFit).
     * ours: XDesk fitted this display before. Returns "set", "cleared", null when nothing was done, or "error: ...".
     */
    String fitPanel(int displayId, int width, int height, boolean ours) = 45;
    /**
     * Tell the listener of every change of the phone's clipboard (IInjectorListener.onClipboard), or stop. Returns
     * whether Android took the watch; without it XDesk sees the clipboard only while it has the focus (ShareHub).
     */
    boolean watchClipboard(boolean on) = 46;
    /** The display's own density, 0 when unknown. */
    int displayDefaultDensity(int displayId) = 23;
    /** Stays the same for a physical display across replugging. Null when unknown. */
    String displayUniqueId(int displayId) = 24;
    /** Close this app's tasks, as swiping them away would; "@<user>" after the package limits it to that user. Returns null or the error. */
    String closeApp(String packageName) = 25;
    /** 触控 mode: two or more fingers, placed around the cursor. Offsets are pixels on the display, ids stay with a finger. */
    oneway void touchFrame(int action, in int[] ids, in float[] offsetX, in float[] offsetY) = 27;
    /** Give input focus to the external display. */
    oneway void focusExternal() = 28;
    /** Ctrl+Alt+M. Off while the external display is covered: input taken back would go to an app nobody can see. */
    oneway void setHotkeyAllowed(boolean allowed) = 29;
    /** The large screen rule of Android 16 for this app: it ignores its orientation lock on any large screen, not on the phone. The system stops the app. Returns null or the error. */
    String setAppFullscreen(String packageName, boolean on) = 30;
    /** Android Back to the app on the external display. */
    oneway void sendBack() = 26;
    /** The session ended: move every normal task on this display to the phone, not closed. Returns null or the error. */
    String returnToPhone(int displayId) = 21;
    /**
     * A display of this session for apps, shown in a surface of XDesk's: the car's screen, see AppDisplay. Ends with the
     * session, and apps on it then go to the phone. The surface may be null. Returns the display id, throws when refused.
     */
    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface, float refreshRate) = 31;
    /** Null detaches: the display and its apps stay, as with the screen off. Returns when done. */
    void setVirtualDisplaySurface(int displayId, in Surface surface) = 32;
    /** A new size for a display of this session, drawing on this surface from now on. Its apps stay. */
    /** The phone's clipboard as text, null without any. */
    String clipboardText() = 40;
    void setClipboardText(String text) = 41;
    /** Where the input method begins on a display, -1 while it is not showing. Slow. */
    int imeTop(int displayId) = 42;
    void resizeVirtualDisplay(int displayId, int width, int height, int dpi, in Surface surface) = 39;
    /** The car's touch screen: fingers at absolute pixels on the display, in any input mode. Action and ids as in MotionEvent. */
    oneway void screenTouch(int action, in int[] ids, in float[] x, in float[] y) = 33;
    /** Keyboard focus to the top task of this display, the phone is 0. */
    oneway void focusDisplay(int displayId) = 34;
    /** Input method windows on this display (on) or back where they were (off). Put back when the session ends. */
    oneway void setImeOnDisplay(int displayId, boolean on) = 35;
    /** A browser with a keyboard of this layout is here, null when not. While input is taken over, a keyboard of that layout exists. */
    oneway void setWebKeyboard(String layout) = 38;
    /** What is in front on this display: a package, empty when not a normal app, null when unreadable. Best effort. */
    String topPackage(int displayId) = 43;
    /** Game passthrough (GAMING.md): nothing grabbed, the external display focused, Ctrl+Alt+G heard; off grabs again. Only from "keys on the external screen". */
    oneway void setGameMode(boolean on) = 44;
    /** The phone's whole sound output as 48 kHz stereo 16-bit PCM, for the wireless display; nothing plays on the phone meanwhile. Close the stream to stop. Throws with the reason. */
    ParcelFileDescriptor captureOutput() = 36;
    /** The phone's on-screen gamepad as a real one (GAMEPAD.md): made if missing, nothing pressed. It stays until the session ends. */
    oneway void gamepadOpen() = 47;
    /** Its whole state: button n (GAMEPAD.md §4) is bit n-1, hat 0-7 clockwise from up or 8 for none, sticks -32768..32767, triggers 0..255. */
    oneway void gamepad(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) = 48;
    /** Nothing pressed; the device stays, so games do not say it went away. */
    oneway void gamepadEnd() = 49;
    // The pointer over apps as the injector's own layer (PointerLayer): its picture (null keeps it), where its point is
    // inside it, whether it shows, and where it is. False: draw it as a window instead.
    boolean pointerLayer(in Bitmap image, int offsetX, int offsetY, boolean visible, int x, int y) = 50;
    // A place for it that the injector did not make itself (XDesk put the pointer somewhere).
    oneway void pointerAt(int x, int y) = 51;
    // The privacy cover: the external screen's Android apps without sound while it hides them (CoverMute).
    oneway void muteApps(boolean on) = 52;
    oneway void observedMouse(int display, float x, float y, boolean active) = 67;
    boolean routeXdesxDesktop(int display) = 65;
    oneway void desktopMouse(int op,float x,float y) = 66;
    void releaseXdesxDisplay(int id) = 60;
    void watchXdesxWindow(int display, com.termux.x11.IXdesxWindowListener callback) = 64;
    int moveXdesxTask(int window, int external, String pkg, int task, boolean enter, in Bitmap cursor, int hotspot, int width, int height) = 62;
    oneway void xdesxNativeInput(int op, float x, float y) = 63;
    oneway void xdesxPointer(int display, int width, int height, int op, int x, int y) = 61;
}
