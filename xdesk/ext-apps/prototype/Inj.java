import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Probe: inject real mouse events (with button state) onto a given display. Runs as the adb shell user. */
public class Inj {
    static Object im; static Method inject, setDisplay, setActionButton;
    static int display;

    static boolean send(long down, int action, int button, int actionButton, float x, float y, float vs) throws Exception {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0; p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x; c.y = y; c.pressure = (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) ? 0f : 1f; c.size = 1f;
        if (action == MotionEvent.ACTION_SCROLL) c.setAxisValue(MotionEvent.AXIS_VSCROLL, vs);
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
            new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
            0, button, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0);
        setDisplay.invoke(e, display);
        if (actionButton != 0) setActionButton.invoke(e, actionButton);
        boolean ok = (Boolean) inject.invoke(im, e, 0);
        e.recycle();
        return ok;
    }

    public static void main(String[] a) throws Exception {
        Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
        im = g.getMethod("getInstance").invoke(null);
        inject = g.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        setDisplay = MotionEvent.class.getMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
        display = Integer.parseInt(a[0]);
        String cmd = a[1];
        float x = Float.parseFloat(a[2]), y = Float.parseFloat(a[3]);
        long t = SystemClock.uptimeMillis();
        if (cmd.equals("move")) {
            System.out.println("hover " + send(t, MotionEvent.ACTION_HOVER_MOVE, 0, 0, x, y, 0));
        } else if (cmd.equals("click")) {
            int b = MotionEvent.BUTTON_PRIMARY;
            System.out.println("hover " + send(t, MotionEvent.ACTION_HOVER_MOVE, 0, 0, x, y, 0));
            Thread.sleep(60);
            long d = SystemClock.uptimeMillis();
            System.out.println("down " + send(d, MotionEvent.ACTION_DOWN, b, 0, x, y, 0));
            System.out.println("press " + send(d, MotionEvent.ACTION_BUTTON_PRESS, b, b, x, y, 0));
            Thread.sleep(60);
            System.out.println("release " + send(d, MotionEvent.ACTION_BUTTON_RELEASE, 0, b, x, y, 0));
            System.out.println("up " + send(d, MotionEvent.ACTION_UP, 0, 0, x, y, 0));
            System.out.println("hover " + send(SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, 0, 0, x, y, 0));
        }
    }
}
