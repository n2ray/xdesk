package com.termux.x11;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descriptor associations are system-wide. Only restore entries this session actually changed. */
final class FullscreenDevices implements InputManager.InputDeviceListener, DisplayManager.DisplayListener, AutoCloseable {
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\s*descriptor: (\\S+)\\s+uniqueId: (\\S+)");
    private final Map<String,String> previous = new HashMap<>();
    private final Map<String,String> initial;
    private final Map<String,String> returnDisplay = new HashMap<>();
    private final Method uniqueId, associatedId;
    private final InputManager inputs;
    private final DisplayManager displays;
    private final int displayId;
    private final Object service;
    private final Method add, remove;
    private final String target;
    private boolean closed;

    FullscreenDevices(int displayId) throws Exception {
        Context context = AppDisplay.shellContext();
        this.displayId = displayId;
        displays = context.getSystemService(DisplayManager.class);
        Display d = displays.getDisplay(displayId);
        if (d == null) throw new IllegalStateException("display removed");
        uniqueId = Display.class.getMethod("getUniqueId");
        associatedId = InputDevice.class.getMethod("getAssociatedDisplayId");
        target = (String) uniqueId.invoke(d);
        if (target == null || target.isEmpty()) throw new IllegalStateException("missing display unique ID");
        Class<?> api = Class.forName("android.hardware.input.IInputManager");
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "input");
        service = Class.forName(api.getName() + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        add = api.getMethod("addUniqueIdAssociationByDescriptor", String.class, String.class);
        remove = api.getMethod("removeUniqueIdAssociationByDescriptor", String.class);
        initial = associations();
        inputs = context.getSystemService(InputManager.class);
        Handler handler = new Handler(Looper.getMainLooper());
        inputs.registerInputDeviceListener(this, handler);
        try {
            displays.registerDisplayListener(this, handler);
            if (displays.getDisplay(displayId) == null) throw new IllegalStateException("display removed");
            for (int id : inputs.getInputDeviceIds()) route(id);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private static Map<String,String> associations() {
        String[] output = InjectorService.exec(3000, "/system/bin/dumpsys", "input");
        if (output[0] != null || !output[1].contains("Input Reader State"))
            throw new IllegalStateException("cannot read previous input routes");
        Matcher matches = ENTRY.matcher(output[1]);
        Map<String,String> routes = new HashMap<>();
        while (matches.find()) routes.put(matches.group(1), matches.group(2));
        return routes;
    }

    private synchronized void route(int id) throws ReflectiveOperationException {
        if (closed) return;
        InputDevice d = inputs.getInputDevice(id);
        if (d == null || !d.isExternal() || d.isVirtual() ||
            !(d.supportsSource(InputDevice.SOURCE_MOUSE) || d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)) return;
        String descriptor = d.getDescriptor();
        if (descriptor == null || previous.containsKey(descriptor) || target.equals(initial.get(descriptor))) return;
        int beforeId = (int) associatedId.invoke(d);
        Display before = displays.getDisplay(beforeId >= 0 ? beforeId : Display.DEFAULT_DISPLAY);
        if (before != null) returnDisplay.put(descriptor, (String) uniqueId.invoke(before));
        add.invoke(service, descriptor, target);
        previous.put(descriptor, initial.get(descriptor));
    }

    public void onInputDeviceAdded(int id) { onInputDeviceChanged(id); }
    public void onInputDeviceChanged(int id) {
        try { route(id); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w("XDesX", "route input device", e); }
    }
    public void onInputDeviceRemoved(int id) { }

    public void onDisplayAdded(int id) { }
    public void onDisplayChanged(int id) { }
    public void onDisplayRemoved(int id) { if (id == displayId) close(); }

    private void awaitViewport(String descriptor, String original) throws ReflectiveOperationException {
        long end = android.os.SystemClock.uptimeMillis() + 250;
        do {
            InputDevice d = null;
            for (int id : inputs.getInputDeviceIds()) {
                InputDevice candidate = inputs.getInputDevice(id);
                if (candidate != null && descriptor.equals(candidate.getDescriptor())) { d = candidate; break; }
            }
            if (d == null) return;
            Display routed = displays.getDisplay((int) associatedId.invoke(d));
            if (routed != null && original.equals(uniqueId.invoke(routed))) return;
            android.os.SystemClock.sleep(10);
        } while (android.os.SystemClock.uptimeMillis() < end);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        inputs.unregisterInputDeviceListener(this);
        displays.unregisterDisplayListener(this);
        for (Map.Entry<String,String> old : previous.entrySet()) {
            try {
                if (old.getValue() == null) {
                    // Some InputReader versions retain the last viewport after removing the mapping.
                    // Deliver the former viewport first, then remove the system-wide override.
                    String original = returnDisplay.get(old.getKey());
                    if (original != null) {
                        add.invoke(service, old.getKey(), original);
                        awaitViewport(old.getKey(), original);
                    }
                    remove.invoke(service, old.getKey());
                }
                else add.invoke(service, old.getKey(), old.getValue());
            } catch (ReflectiveOperationException | RuntimeException e) { Log.w("XDesX", "restore input device", e); }
        }
        previous.clear();
    }
}
