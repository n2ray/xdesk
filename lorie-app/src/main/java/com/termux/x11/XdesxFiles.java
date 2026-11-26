// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** One emulated-storage folder shared with Burrow. Android grants access per document or tree. */
public final class XdesxFiles extends DocumentsProvider {
    private static final String ROOT = "shared";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON};
    private static final String[] FILE_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    static File sharedRoot(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(dir))
                || !Environment.isExternalStorageEmulated(dir)) return null;
        // Emulated storage disallows symlinks and hard links, including from guest processes.
        File root = new File(dir, ROOT);
        return root.isDirectory() || root.mkdirs() ? root : null;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor rows = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        if (sharedRoot(getContext()) != null)
            rows.newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_TITLE, "xdesx").add(Root.COLUMN_ICON, com.termux.x11.app.R.drawable.xdesx_icon)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY);
        return rows;
    }

    private File resolve(String id) throws FileNotFoundException {
        File root = sharedRoot(getContext());
        if (root == null) throw new FileNotFoundException("Storage unavailable");
        if (ROOT.equals(id)) return root;
        if (id == null || !id.startsWith(ROOT + "/")) throw new FileNotFoundException();
        File file = root;
        for (String name : id.substring(ROOT.length()+1).split("/", -1))
            file = new File(file, validName(name));
        if (!file.exists()) throw new FileNotFoundException();
        return file;
    }

    private static String validName(String name) throws FileNotFoundException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf(0) >= 0) throw new FileNotFoundException("Invalid name");
        return name;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        try {
            resolve(parent); resolve(child);
            return child.startsWith(parent + "/");
        } catch (FileNotFoundException e) { return false; }
    }

    private void add(MatrixCursor rows, File f, String id) {
        boolean dir = f.isDirectory();
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));
        rows.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, ROOT.equals(id) ? "xdesx" : name)
            .add(Document.COLUMN_MIME_TYPE, dir ? Document.MIME_TYPE_DIR : mime == null ? "application/octet-stream" : mime)
            .add(Document.COLUMN_FLAGS, (dir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE)
                    | (ROOT.equals(id) ? 0 : Document.FLAG_SUPPORTS_RENAME))
            .add(Document.COLUMN_SIZE, dir ? null : f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(projection == null ? FILE_COLUMNS : projection);
        add(rows, resolve(id), id);
        return rows;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(projection == null ? FILE_COLUMNS : projection);
        File[] files = resolve(parent).listFiles();
        if (files == null) throw new FileNotFoundException();
        for (File f : files) add(rows, f, parent + "/" + f.getName());
        return rows;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File f = resolve(id);
        if (!f.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        File f = new File(resolve(parent), validName(name));
        try {
            if (Document.MIME_TYPE_DIR.equals(mime) ? f.mkdir() : f.createNewFile()) return parent + "/" + name;
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        throw new FileNotFoundException("Name already exists or cannot be created");
    }

    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        if (ROOT.equals(id)) throw new FileNotFoundException();
        File f = resolve(id);
        File dest = new File(f.getParentFile(), validName(name));
        if (dest.exists() || !f.renameTo(dest)) throw new FileNotFoundException();
        return id.substring(0, id.lastIndexOf('/') + 1) + name;
    }
}
