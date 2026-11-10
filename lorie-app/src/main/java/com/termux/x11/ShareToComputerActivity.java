package com.termux.x11;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import com.termux.x11.app.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Send to computer" in the system's share sheet. The permission to read what was shared ends with this activity, so
 * the files are opened here and sent after it has gone.
 */
public final class ShareToComputerActivity extends Activity {
    private static final String TAG = "XDeskWireless";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WirelessDisplay wireless = WirelessDisplay.get(this);
        if (!wireless.canSendFiles()) {
            Toast.makeText(this, R.string.xd_share_no_computer, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        List<WirelessDisplay.OutgoingFile> files = new ArrayList<>();
        for (Uri uri : shared()) {
            WirelessDisplay.OutgoingFile f = open(uri);
            if (f != null) files.add(f);
        }
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.xd_share_nothing, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.xd_share_sending, wireless.receiverName()), Toast.LENGTH_SHORT).show();
            wireless.sendFiles(files);
        }
        finish();
    }

    private List<Uri> shared() {
        Intent i = getIntent();
        List<Uri> found = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri one = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) found.add(one);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> many = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) found.addAll(many);
        }
        return found;
    }

    /** Anyone can start this activity: only what another app's provider hands out, nothing of XDesk's own. */
    private boolean allowed(Uri uri) {
        String authority = uri.getAuthority();
        return "content".equals(uri.getScheme()) && authority != null && !authority.startsWith(getPackageName());
    }

    private WirelessDisplay.OutgoingFile open(Uri uri) {
        if (!allowed(uri)) return null;
        try {
            ParcelFileDescriptor file = getContentResolver().openFileDescriptor(uri, "r");
            return file != null ? new WirelessDisplay.OutgoingFile(nameOf(uri), file) : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot open what was shared", e);
            return null;
        }
    }

    private String nameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "no name for what was shared", e);
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }
}
