package io.github.n2ray.xdesk.probe;
import android.os.ParcelFileDescriptor;
interface IProbe {
    int serviceUid();
    String handshake(in ParcelFileDescriptor endpoint, in ParcelFileDescriptor registration, String protocol);
    String runSocket(in ParcelFileDescriptor endpoint, String deniedPath);
    String run(in ParcelFileDescriptor marker, String ownerPath);
}
