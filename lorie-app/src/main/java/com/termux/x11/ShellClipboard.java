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
    private Method get, set;

    ShellClipboard() throws ReflectiveOperationException {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        service = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        for (Method m : service.getClass().getMethods()) {
            if (m.getName().equals("getPrimaryClip")) get = m;
            if (m.getName().equals("setPrimaryClip")) set = m;
        }
        if (get == null || set == null) throw new NoSuchMethodException("clipboard");
    }

    private static Object[] arguments(Method m, ClipData clip) {
        Class<?>[] types = m.getParameterTypes();
        Object[] values = new Object[types.length];
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ClipData.class) values[i] = clip;
            else if (types[i] == String.class) values[i] = strings++ == 0 ? PACKAGE : null;
            else if (types[i] == int.class) values[i] = 0;
        }
        return values;
    }

    /** Null when the clipboard holds no text. */
    String text() throws ReflectiveOperationException {
        ClipData clip = (ClipData) get.invoke(service, arguments(get, null));
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return null;
        return clip.getItemAt(0).getText().toString();
    }

    void setText(String text) throws ReflectiveOperationException {
        ClipData clip = new ClipData(new ClipDescription("XDesk", new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}), new ClipData.Item(text));
        set.invoke(service, arguments(set, clip));
    }
}
