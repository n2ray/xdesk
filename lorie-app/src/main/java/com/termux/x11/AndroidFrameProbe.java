package com.termux.x11;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.hardware.HardwareBuffer;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.IOException;

/** Experiment: transfer a shell-created display frame to the same UID's built-in X server. */
public final class AndroidFrameProbe extends ContentProvider {
    private static native int send(HardwareBuffer buffer, int fd);

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != 2000 || !"frame".equals(method))
            throw new SecurityException("shell frame probe only");
        System.loadLibrary("xdesk_android_frame");
        try (HardwareBuffer buffer = extras.getParcelable("buffer");
             LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("xdesk-android-frame-probe", LocalSocketAddress.Namespace.ABSTRACT));
            if (socket.getPeerCredentials().getUid() != Process.myUid())
                throw new SecurityException("expected built-in Linux");
            socket.setSoTimeout(3000);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.dup(socket.getFileDescriptor())) {
                if (send(buffer, fd.getFd()) != 0 || socket.getInputStream().read() != 1)
                    throw new IOException("frame not consumed");
            }
            return Bundle.EMPTY;
        } catch (IOException e) {
            throw new IllegalStateException("Android frame transport", e);
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
}
