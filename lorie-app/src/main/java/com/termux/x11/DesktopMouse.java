package com.termux.x11;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Feed touchpad gestures into Android's HDMI mouse seat, alongside physical mice. */
final class DesktopMouse implements Closeable {
    private static final int[] HID = {
        5,1,9,2,161,1,9,1,161,0,5,9,25,1,41,3,21,0,37,1,117,1,149,3,129,2,
        117,5,149,1,129,3,5,1,9,48,9,49,22,0,128,38,255,127,117,16,149,2,129,6,
        9,56,21,129,37,127,117,8,149,1,129,6,5,12,10,56,2,129,6,192,192
    };
    private final FileOutputStream device;
    private final ByteBuffer report=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
    private int buttons;
    private float dx,dy,vertical,horizontal;

    DesktopMouse() throws IOException {
        device=new FileOutputStream("/dev/uhid");
        ByteBuffer b=ByteBuffer.allocate(4380).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(11);
        b.put("xdesx Touchpad".getBytes(StandardCharsets.US_ASCII));
        b.position(132); b.put("xdesx/pad".getBytes(StandardCharsets.US_ASCII));
        b.position(196); b.put("xdesx-touchpad".getBytes(StandardCharsets.US_ASCII));
        b.position(260); b.putShort((short)HID.length).putShort((short)3).putInt(0x18d1).putInt(0x5844).putInt(1).putInt(0);
        for(int v:HID)b.put((byte)v);
        try {device.write(b.array());} catch(IOException e){device.close();throw e;}
    }
    synchronized void input(int op,float x,float y) throws IOException {
        if(op==1){dx+=x;dy+=y;}
        else if(op==2){
            int mask=(int)x==1?1:(int)x==2?4:2;
            if(y!=0) buttons|=mask; else buttons&=~mask;
        } else if(op==3){vertical-=y;horizontal+=x;}
        else return;
        int mx=Math.max(-32767,Math.min(32767,(int)dx)),my=Math.max(-32767,Math.min(32767,(int)dy));
        int v=Math.max(-127,Math.min(127,(int)vertical)),h=Math.max(-127,Math.min(127,(int)horizontal));
        dx-=mx;dy-=my;vertical-=v;horizontal-=h;
        if(op!=2 && mx==0 && my==0 && v==0 && h==0)return;
        report.clear();report.putInt(12).putShort((short)7).put((byte)buttons).putShort((short)mx).putShort((short)my).put((byte)v).put((byte)h);
        device.write(report.array());
    }
    public synchronized void close() throws IOException {device.close();}
}
