import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Duration;

/** Shell-side test producer. stdout reports the virtual display ID for am start --display. */
public final class ChromeFrames {
    public static void main(String[] args) throws Exception {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); System.exit(1); }
        System.exit(0);
    }

    private static void run() throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        Context system = (Context)thread.getMethod("getSystemContext").invoke(instance);
        Context shell = system.createPackageContext("com.android.shell", 0);
        DisplayManager manager = DisplayManager.class.getDeclaredConstructor(Context.class).newInstance(shell);
        final Uri target = Uri.parse("content://io.github.n2ray.xdesk.x11.androidframe");
        final int width = 720, height = 480;
        try (ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
            // PUBLIC, OWN_CONTENT_ONLY, DESTROY_CONTENT_ON_REMOVAL, TRUSTED, OWN_FOCUS, STEAL_TOP_FOCUS_DISABLED.
            VirtualDisplay display = manager.createVirtualDisplay("XD33-glamor", width, height, 160,
                    reader.getSurface(), 1 | 8 | 256 | 1024 | 16384 | 65536);
            if (display == null) throw new IllegalStateException("no virtual display");
            try {
                System.out.println("DISPLAY " + display.getDisplay().getDisplayId());
                System.out.flush();
                long start = SystemClock.elapsedRealtime();
                int count = 0;
                while (SystemClock.elapsedRealtime() - start < 18000) {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image == null) { Thread.sleep(4); continue; }
                        try (SyncFence fence = image.getFence(); HardwareBuffer buffer = image.getHardwareBuffer()) {
                            if (fence.isValid() && !fence.await(Duration.ofSeconds(1)))
                                throw new IllegalStateException("frame acquire fence timeout");
                            Bundle args = new Bundle();
                            args.putParcelable("buffer", buffer);
                            if (shell.getContentResolver().call(target, "frame", null, args) == null)
                                throw new IllegalStateException("no consumer reply");
                            count++;
                        }
                    }
                }
                System.out.println("ACKNOWLEDGED " + count);
            } finally { display.release(); }
        }
    }
}
