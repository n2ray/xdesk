package com.termux.x11;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

/** A user-enabled, passive mouse subscription. Chrome remains the input recipient in both modes. */
public final class HdmiMouseObserver extends AccessibilityService {
    private static HdmiMouseObserver instance;
    private static IInjector injector;
    private static int display = -1;
    private boolean subscribed;
    private java.lang.reflect.Method eventDisplay;

    // Activity and accessibility callbacks run on the same main looper.
    static void route(IInjector target, int id) {
        if (instance != null) instance.subscribe(false);
        injector = target;
        display = id;
        if (instance != null && target != null) instance.subscribe(true);
    }

    @Override protected void onServiceConnected() {
        instance = this;
        if (injector != null) subscribe(true);
    }

    private void subscribe(boolean enable) {
        if (subscribed == enable || android.os.Build.VERSION.SDK_INT < 35) return;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            int sources = enable ? InputDevice.SOURCE_MOUSE : 0;
            if (enable && !Boolean.TRUE.equals(Class.forName("android.view.accessibility.Flags")
                    .getMethod("motionEventObserving").invoke(null))) return;
            if (enable) {
                eventDisplay = android.view.InputEvent.class.getMethod("getDisplayId");
                info.setMotionEventSources(sources);
            }
            AccessibilityServiceInfo.class.getMethod("setObservedMotionEventSources", int.class).invoke(info, sources);
            if (!enable) info.setMotionEventSources(0);
            setServiceInfo(info);
            subscribed = enable;
            injector.observedMouse(display, Float.NaN, Float.NaN, enable);
        } catch (ReflectiveOperationException | RuntimeException | RemoteException e) {
            Log.w("XDesX", "Passive HDMI mouse subscription unavailable", e);
        }
    }

    @Override public void onMotionEvent(MotionEvent e) {
        if (!subscribed || injector == null || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return;
        try {
            if ((int) eventDisplay.invoke(e) == display)
                injector.observedMouse(display, e.getRawX(), e.getRawY(), true);
        } catch (RemoteException | ReflectiveOperationException ignored) { }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent ignored) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        subscribe(false);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
