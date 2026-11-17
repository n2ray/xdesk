package com.termux.x11;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.IBinder;
import java.lang.reflect.Method;

/**
 * The phone's clipboard, from the shell-user process, which Android lets read it from the background. Reflection on the
 * system service, whose methods gain arguments between Android versions: strings are the package then the attribution tag.
 */
final class ShellClipboard {
    private static final String PACKAGE = "com.android.shell";
    private final Object service;
    private Method get, set, addListener, removeListener;
    /** The watch's listener as Android's interface (a proxy over our binder), null while none. */
    private Object watching;

    ShellClipboard() throws ReflectiveOperationException {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        service = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        for (Method m : service.getClass().getMethods()) {
            if (m.getName().equals("getPrimaryClip")) get = m;
            if (m.getName().equals("setPrimaryClip")) set = m;
            if (m.getName().equals("addPrimaryClipChangedListener")) addListener = m;
            if (m.getName().equals("removePrimaryClipChangedListener")) removeListener = m;
        }
        if (get == null || set == null) throw new NoSuchMethodException("clipboard");
    }

    private static Object[] arguments(Method m, ClipData clip) {
        return arguments(m, clip, null);
    }

    private static Object[] arguments(Method m, ClipData clip, Object listener) {
        Class<?>[] types = m.getParameterTypes();
        Object[] values = new Object[types.length];
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ClipData.class) values[i] = clip;
            else if (listener != null && types[i].isInstance(listener)) values[i] = listener;
            else if (types[i] == String.class) values[i] = strings++ == 0 ? PACKAGE : null;
            else if (types[i] == int.class) values[i] = 0;
        }
        return values;
    }

    /**
     * A password manager's copy (ClipDescription.EXTRA_IS_SENSITIVE, Android 13): not for the other devices. Before the
     * injector's watch they got the phone's copies only while XDesk had the focus (audit, 17 November).
     */
    static boolean sensitive(ClipData clip) {
        android.os.PersistableBundle extras = clip.getDescription() != null ? clip.getDescription().getExtras() : null;
        return extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false);
    }

    /** Null when the clipboard holds no text, or text marked sensitive. */
    String text() throws ReflectiveOperationException {
        ClipData clip = (ClipData) get.invoke(service, arguments(get, null));
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null || sensitive(clip)) return null;
        return clip.getItemAt(0).getText().toString();
    }

    /**
     * Calls onChange (on a binder thread) at each change of the clipboard, until unwatch. The listener's interface is
     * hidden, so it is Android's own proxy over a plain binder that answers its one call (oneway, FIRST_CALL_TRANSACTION).
     * The shell may read the clipboard in the background, and Android tells a listener only what it may read.
     */
    synchronized void watch(final Runnable onChange) throws ReflectiveOperationException {
        if (addListener == null || watching != null) {
            if (addListener == null) throw new NoSuchMethodException("addPrimaryClipChangedListener");
            return;
        }
        final String descriptor = "android.content.IOnPrimaryClipChangedListener";
        android.os.Binder b = new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                    throws android.os.RemoteException {
                if (code == FIRST_CALL_TRANSACTION) {
                    try {
                        onChange.run();
                    } catch (RuntimeException ignored) { }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        b.attachInterface(null, descriptor);
        Object listener = Class.forName(descriptor + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        addListener.invoke(service, arguments(addListener, null, listener));
        watching = listener;
    }

    synchronized void unwatch() {
        if (watching == null || removeListener == null) return;
        try {
            removeListener.invoke(service, arguments(removeListener, null, watching));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        watching = null;
    }

    void setText(String text) throws ReflectiveOperationException {
        ClipData clip = new ClipData(new ClipDescription("XDesk", new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}), new ClipData.Item(text));
        set.invoke(service, arguments(set, clip));
    }
}
