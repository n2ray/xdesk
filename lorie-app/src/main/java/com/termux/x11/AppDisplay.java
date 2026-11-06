package com.termux.x11;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Process;
import android.view.Surface;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * A display for Android apps on the car, made by the shell user and drawn into CarActivity's SurfaceView. Android Auto's
 * own display for CarActivity only takes Android Auto's and XDesk's activities, this one takes any app.
 *
 * Made the way scrcpy makes its "new display" as shell (Genymobile/scrcpy, Apache License 2.0: NewDisplayCapture and
 * wrappers/DisplayManager): a DisplayManager on a context that says it is the shell package, because the system checks
 * the package name against the calling uid. The flags are chosen from scrcpy's, see flags().
 */
final class AppDisplay {
    private static final String SHELL_PACKAGE = "com.android.shell";

    /** Hidden in DisplayManager, values as in Android 13 to 16. */
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6, FLAG_TRUSTED = 1 << 10, FLAG_OWN_DISPLAY_GROUP = 1 << 11,
        FLAG_ALWAYS_UNLOCKED = 1 << 12, FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;

    private AppDisplay() { }

    /**
     * - PUBLIC: other apps may be started on it and XDesk may put its cursor window on it.
     * - OWN_CONTENT_ONLY: shows only what is on it. Without it a public display mirrors the phone.
     * - SUPPORTS_TOUCH: apps see a touch screen, which is what the car gives.
     * - TRUSTED: activities of any app may go there without allowEmbedded, and system windows such as XDesk's
     *   TYPE_APPLICATION_OVERLAY cursor are allowed.
     * - ALWAYS_UNLOCKED: the phone's lock screen does not cover it. The phone is usually locked in the car.
     * - OWN_DISPLAY_GROUP: its power state is not the phone's, so it is not put to sleep with the phone's screen.
     * - TOUCH_FEEDBACK_DISABLED: taps on the car do not make the phone vibrate.
     * Not set: DESTROY_CONTENT_ON_REMOVAL, so apps go to the phone when it ends, as from an unplugged display. SECURE,
     * which the shell may not ask for, so DRM video may be black. ROTATES_WITH_CONTENT, because the car's screen does not
     * turn: a portrait app gets bars instead of a sideways picture. PRESENTATION, so apps do not put their own
     * presentations there. OWN_FOCUS, so focus moves between displays as it does for an external display.
     */
    private static int flags() {
        int f = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            f |= FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED | FLAG_TOUCH_FEEDBACK_DISABLED;
        }
        return f;
    }

    /** Throws IllegalStateException with the reason, which Binder carries back to XDesk. */
    static VirtualDisplay create(String name, int width, int height, int dpi, Surface surface) {
        try {
            Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
            c.setAccessible(true);
            DisplayManager dm = c.newInstance(new ShellContext(systemContext()));
            VirtualDisplay d = dm.createVirtualDisplay(name, width, height, dpi, surface, flags());
            if (d == null) throw new IllegalStateException("系统没有建出显示器");
            return d;
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(String.valueOf(e.getCause()));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException(e.toString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            // SecurityException and others, as text: Binder only carries some exception types
            throw new IllegalStateException(e.toString());
        }
    }

    /** Both hosts have a system ActivityThread, see ShellServer.matchShizukuEnvironment. */
    private static Context systemContext() throws ReflectiveOperationException {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override public String getPackageName() {
            return SHELL_PACKAGE;
        }

        @Override public String getOpPackageName() {
            return SHELL_PACKAGE;
        }

        /** The system context would name the package "android", which uid 2000 does not own. */
        @TargetApi(Build.VERSION_CODES.S)
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build();
        }
    }
}
