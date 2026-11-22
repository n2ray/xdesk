package io.github.n2ray.xdesk.probe;
import android.os.ParcelFileDescriptor;
interface IProbe {
    String run(in ParcelFileDescriptor marker, String ownerPath);
}
