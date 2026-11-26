package android.window;
import android.util.Pair;
import android.view.InputWindowHandle;
public abstract class WindowInfosListener {
    public static class DisplayInfo {}
    public abstract void onWindowInfosChanged(InputWindowHandle[] windows, DisplayInfo[] displays);
    public native Pair<InputWindowHandle[], DisplayInfo[]> register();
    public native void unregister();
}
