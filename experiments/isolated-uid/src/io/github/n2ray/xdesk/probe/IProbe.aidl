package io.github.n2ray.xdesk.probe;
import android.os.ParcelFileDescriptor;
interface IProbe {
    String runSocket(in ParcelFileDescriptor endpoint, String deniedPath);
    String run(in ParcelFileDescriptor marker, String ownerPath);
}
