/*
 * Derived from Sall (https://github.com/n2ray/sall, PairScannerActivity.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.termux.x11.app.R;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * Reads only Sill pairing codes, as Sall's scanner does: a camera page with one cancel button. The scanned link
 * goes to the one receiver set before the start (MacSettings' "Scan QR code"): the link carries the pairing secret,
 * so it never goes through an Intent.
 */
public final class PairScannerActivity extends Activity {
    private static final int CAMERA_REQUEST = 1;
    /** One scan at a time; null when none is open. Weak: the receiver's page must not stay alive for the field. */
    private static java.lang.ref.WeakReference<Consumer<String>> pending;

    /** Sets the receiver of the next scanned link and starts the scanner. False when a scan is already open. */
    public static synchronized boolean start(Activity from, Consumer<String> onLink) {
        if (pending != null && pending.get() != null) return false;
        pending = new java.lang.ref.WeakReference<>(onLink);
        try {
            from.startActivity(new Intent(from, PairScannerActivity.class));
            return true;
        } catch (RuntimeException e) {
            pending = null;
            throw e;
        }
    }

    private BarcodeView camera;
    private TextView status;
    private boolean active, completed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The QR code is a credential: keep it out of screenshots, as Sall's scanner does
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xff101215);
        TextView title = new TextView(this);
        title.setText(getString(R.string.xd_mac_scan_title));
        title.setTextColor(0xffeef0f3);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        root.addView(title);
        camera = new BarcodeView(this);
        camera.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        root.addView(camera, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this);
        status.setTextColor(0xffa4abb5);
        status.setTextSize(13);
        status.setPadding(0, dp(12), 0, dp(8));
        status.setText(getString(R.string.xd_mac_scan_status));
        root.addView(status);
        camera.addStateListener(new CameraPreview.StateListener() {
            public void previewSized() {}
            public void previewStarted() {}
            public void previewStopped() {}
            public void cameraClosed() {}
            public void cameraError(Exception error) { status.setText(getString(R.string.xd_mac_scan_camera_error)); }
        });
        Button close = new Button(this);
        close.setText(getString(R.string.xd_cancel));
        close.setAllCaps(false);
        close.setTextSize(15);
        close.setTextColor(0xff9bb7ff);
        close.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        close.setMinHeight(dp(44));
        close.setOnClickListener(v -> finish());
        root.addView(close);
        setContentView(root);
        camera.decodeContinuous(result -> {
            if (completed) return;
            String raw = result.getText();
            try {
                // Only a well-formed link is a scan: it is still only offered, never trusted, until the user confirms
                PairLink parsed = PairLink.parse(raw);
                parsed.clearSecret();
            } catch (IllegalArgumentException e) {
                status.setText(getString(R.string.xd_mac_scan_bad));
                return;
            }
            completed = true;
            Consumer<String> receiver;
            synchronized (PairScannerActivity.class) {
                java.lang.ref.WeakReference<Consumer<String>> r = pending;
                pending = null;
                receiver = r == null ? null : r.get();
            }
            finish();
            if (receiver != null) receiver.accept(raw);
        });
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            status.setText(getString(R.string.xd_mac_scan_no_camera));
        else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        startCamera();
    }

    @Override protected void onPause() {
        active = false;
        camera.pause();
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText(getString(R.string.xd_mac_scan_denied));
    }

    /** Leaving without a scan (cancel, back) leaves no pending receiver behind; a recreation keeps it. */
    @Override protected void onDestroy() {
        super.onDestroy();
        if (!completed && !isChangingConfigurations())
            synchronized (PairScannerActivity.class) { pending = null; }
    }

    private void startCamera() {
        if (active && !completed && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) camera.resume();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
