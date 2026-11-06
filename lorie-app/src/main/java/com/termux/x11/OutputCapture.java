package com.termux.x11;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The phone's whole sound output, for the wireless display. Runs in the shell-user process, which holds
 * CAPTURE_AUDIO_OUTPUT. REMOTE_SUBMIX, as scrcpy's "output" source: while it records, the system plays nothing on the
 * phone, so desktop audio (XDesk's own AudioTrack), Android apps and system sounds all go to the receiver, once.
 *
 * The PCM (48 kHz, stereo, 16-bit little-endian) goes into a pipe whose other end XDesk reads. Closing that end, or
 * stop(), ends the recording.
 */
final class OutputCapture {
    private static final String TAG = "XDeskInjector";
    static final int RATE = 48000;
    private static final int FRAME = 4, CHUNK = RATE * FRAME / 50;

    private AudioRecord record;
    private Thread thread;

    synchronized ParcelFileDescriptor start() {
        stop();
        AudioRecord r;
        try {
            AudioRecord.Builder b = new AudioRecord.Builder();
            // The system checks the package against the uid on Android 12 and later
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setContext(AppDisplay.shellContext());
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            r = b.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build())
                .setBufferSizeInBytes(Math.max(min, 8 * CHUNK))
                .build();
            r.startRecording();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // As text: Binder only carries some exception types
            throw new IllegalStateException(e.toString());
        }
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            r.release();
            throw new IllegalStateException(e.toString());
        }
        record = r;
        final ParcelFileDescriptor out = pipe[1];
        final AudioRecord rec = r;
        thread = new Thread(() -> pump(rec, out), "xdesk-output-capture");
        thread.start();
        Log.i(TAG, "capturing the sound output");
        return pipe[0];
    }

    private void pump(AudioRecord r, ParcelFileDescriptor pfd) {
        byte[] buf = new byte[CHUNK];
        try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            while (!Thread.currentThread().isInterrupted()) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n > 0) out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Log.i(TAG, "sound output reader gone: " + e.getMessage());
        } finally {
            try {
                pfd.close();
            } catch (IOException ignored) { }
            synchronized (this) {
                if (record == r) {
                    record = null;
                    thread = null;
                }
            }
            try {
                r.stop();
            } catch (IllegalStateException ignored) { }
            r.release();
        }
    }

    synchronized void stop() {
        Thread t = thread;
        AudioRecord r = record;
        thread = null;
        record = null;
        if (t != null) t.interrupt();
        // read() may block; stop() makes it return
        if (r != null) {
            try {
                r.stop();
            } catch (IllegalStateException ignored) { }
        }
    }
}
