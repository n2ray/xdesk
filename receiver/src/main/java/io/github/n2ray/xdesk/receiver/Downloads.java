package io.github.n2ray.xdesk.receiver;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Files the phone sends, one at a time, on the session's thread. Into Downloads on Android 10 and later; before that
 * into this app's own Download folder, which needs no permission.
 */
final class Downloads {
    interface Saved {
        void saved(String where);
    }

    private final Context context;
    private final Saved saved;
    private OutputStream out;
    private Uri uri;
    private File file;
    private String name;

    Downloads(Context c, Saved saved) {
        context = c.getApplicationContext();
        this.saved = saved;
    }

    /** An empty name drops what came of the file so far. */
    void start(String name) {
        drop();
        name = clean(name);
        if (name.isEmpty()) return;
        this.name = name;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver cr = context.getContentResolver();
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("no place in Downloads");
                out = cr.openOutputStream(uri);
            } else {
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IOException("no external storage");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
                file = unique(dir, name);
                out = new FileOutputStream(file);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(MainActivity.TAG, "file " + name + ": " + e);
            drop();
        }
    }

    void data(byte[] b, int n) {
        if (out == null) return;
        try {
            out.write(b, 0, n);
        } catch (IOException e) {
            Log.w(MainActivity.TAG, "file " + name + ": " + e);
            drop();
        }
    }

    void end() {
        if (out == null) return;
        try {
            out.close();
            out = null;
            if (uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, v, null, null);
                saved.saved(Environment.DIRECTORY_DOWNLOADS + "/" + name);
            } else if (file != null) {
                saved.saved(file.getPath());
            }
        } catch (IOException | RuntimeException e) {
            Log.w(MainActivity.TAG, "file " + name + ": " + e);
            drop();
        }
        uri = null;
        file = null;
    }

    /** The session ended or the file went wrong: nothing half-written stays. */
    void drop() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                // Deleting it anyway
            }
        }
        out = null;
        try {
            if (uri != null) context.getContentResolver().delete(uri, null, null);
        } catch (RuntimeException e) {
            Log.w(MainActivity.TAG, "dropping " + name + ": " + e);
        }
        if (file != null && file.exists() && !file.delete()) Log.w(MainActivity.TAG, "cannot delete " + file);
        uri = null;
        file = null;
    }

    /** A name, not a path. */
    private static String clean(String name) {
        name = name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 200) name = name.substring(name.length() - 200);
        return name;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }
}
