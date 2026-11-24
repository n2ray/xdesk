import android.content.AttributionSource;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.*;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** One shell producer, two independently sized displays. Only one handle transfer is outstanding. */
public final class TwoDisplays {
    static final String AUTHORITY = "io.github.n2ray.xdesk.x11.androidframe";

    static final class Slot implements AutoCloseable {
        final String name;
        VirtualDisplay display;
        ImageReader reader;
        Slot(String name) { this.name = name; }

        void configure(DisplayManager manager, int w, int h) {
            if (w == 0 && h == 0) { close(); return; }
            if (w <= 0 || h <= 0 || (long)w*h > 16777216)
                throw new IllegalArgumentException("unsupported window " + w + "x" + h);
            if (reader != null && reader.getWidth() == w && reader.getHeight() == h) return;
            ImageReader next = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            try {
                if (display == null) {
                    display = manager.createVirtualDisplay("XDesk dual " + name,w,h,160,next.getSurface(),
                            1 | 8 | 256 | 1024 | 16384 | 65536);
                    if (display == null) throw new IllegalStateException("no display for " + name);
                } else {
                    display.resize(w,h,160);
                    display.setSurface(next.getSurface());
                }
            } catch (RuntimeException e) { next.close(); throw e; }
            if (reader != null) reader.close();
            reader = next;
            System.out.println(name + " display=" + display.getDisplay().getDisplayId() + " size=" + w + "x" + h);
        }

        public void close() {
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
        }
    }

    public static void main(String[] args) {
        try { run(Integer.parseInt(args[0]),args[1]); }
        catch (Throwable e) { e.printStackTrace(System.out); System.exit(1); }
        System.exit(0);
    }

    static void run(int port, String token) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Context base = (Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Context shell = base.createPackageContext("com.android.shell",0);
        DisplayManager manager = DisplayManager.class.getDeclaredConstructor(Context.class).newInstance(shell);
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> iam = Class.forName("android.app.IActivityManager");
        IBinder binder = new Binder();
        Object holder = iam.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
                .invoke(am,AUTHORITY,0,binder,"TwoDisplays");
        Method call = Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,
                String.class,String.class,String.class,Bundle.class);
        AttributionSource attribution = new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
        try (Socket ctrl = new Socket(InetAddress.getByName("127.0.0.1"),port);
             Slot chrome = new Slot("Chrome"); Slot youtube = new Slot("YouTube")) {
            Object provider = holder.getClass().getField("provider").get(holder);
            ctrl.setTcpNoDelay(true); ctrl.setSoTimeout(7000);
            DataOutputStream out = new DataOutputStream(ctrl.getOutputStream());
            DataInputStream in = new DataInputStream(new BufferedInputStream(ctrl.getInputStream()));
            out.write(token.getBytes(StandardCharsets.US_ASCII));
            Slot[] slots = {chrome,youtube};
            long nextCheck = 0;
            while (true) {
                if (SystemClock.uptimeMillis() >= nextCheck) {
                    out.writeByte(0);
                    for (Slot s : slots) s.configure(manager,in.readInt(),in.readInt());
                    if (chrome.reader == null && youtube.reader == null) break;
                    nextCheck = SystemClock.uptimeMillis()+80;
                }
                boolean drew = false;
                for (int i=0;i<slots.length;i++) {
                    ImageReader reader=slots[i].reader;
                    if (reader == null) continue;
                    try (Image image=reader.acquireLatestImage()) {
                        if (image==null) continue;
                        try (HardwareBuffer buffer=image.getHardwareBuffer(); SyncFence fence=image.getFence()) {
                            if (fence.isValid() && !fence.await(Duration.ofSeconds(1))) continue;
                            int w=buffer.getWidth(), h=buffer.getHeight();
                            if (w!=reader.getWidth() || h!=reader.getHeight()) continue;
                            // The TCP header identifies the very next buffer on the APK's existing Unix socket.
                            ByteArrayOutputStream header=new ByteArrayOutputStream(9);
                            DataOutputStream fields=new DataOutputStream(header);
                            fields.writeByte(i+1); fields.writeInt(w); fields.writeInt(h);
                            out.write(header.toByteArray());
                            Bundle args=new Bundle(); args.putParcelable("buffer",buffer);
                            if (call.invoke(provider,attribution,AUTHORITY,"frame",null,args)==null)
                                throw new IOException("no consumer acknowledgement");
                            drew=true;
                        }
                    }
                }
                if (!drew) Thread.sleep(5);
            }
        } finally {
            iam.getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(am,AUTHORITY,binder);
        }
    }
}
