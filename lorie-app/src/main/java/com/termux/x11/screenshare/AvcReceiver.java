package com.termux.x11.screenshare;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import static com.termux.x11.screenshare.DirectSession.*;

final class AvcReceiver {
    interface Sink {void frame(byte[] data,long ns,int flags,long dueNs)throws Exception;}
    private SrtpStream stream;
    private final MediaClock clock;
    private MediaClock.Reports reports;
    private int mediaSsrc;
    volatile boolean suspended;
    final TreeMap<Long,byte[]> parts=new TreeMap<>();
    long firstNs, stamp=-1;
    int frames, lost, accepted, meta, idrs, firs;
    boolean haveSsrc;
    byte[] sps,pps;
    private int seenTypes;
    KeyframeFeedback feedback;
    private long nextRecovery, nextKeyframeAsk;
    /** XDesk: set when a decoder was (re)attached; cleared by the next IDR. */
    volatile boolean wantKeyframe;
    /** HEVC: its access units are assembled here instead (ScreenShareSession sets it before the first packet). */
    volatile HevcPicture hevc;
    /** The last packet's index, to see a packet missing between pictures too (-1: none yet). */
    private long lastIndex = -1;
    int gaps;
    AvcReceiver(byte[] seed, MediaClock clock)throws Exception{
        this.clock = clock;
        reports = new MediaClock.Reports(seed);
        stream = new SrtpStream(seed);
    }
    synchronized void restart(byte[] key,KeyframeFeedback next)throws Exception {
        stream=new SrtpStream(key); reports=new MediaClock.Reports(key);
        feedback=next;parts.clear();stamp=-1;lastIndex=-1;haveSsrc=false;
        nextRecovery=nextKeyframeAsk=0;sps=pps=null;seenTypes=0;wantKeyframe=true;
        if(hevc!=null)hevc=new HevcPicture();
    }
    synchronized void hide() {
        suspended = true;
        parts.clear();
        stamp = -1;
        lastIndex = -1;
    }

    synchronized void packet(byte[] p,int n,long ns,Sink sink)throws Exception{
        // Track authenticated sequence rollovers while hidden so a long detach can resume.
        if (n >= 2 && (p[1] & 255) == 200) {
            reports.accept(p, n, clock.video);
            return;
        }
        SrtpStream.Payload payload = stream.open(p, n);
        if (payload == null || suspended) return;
        long index = payload.index;
        byte[] pay = payload.data;
        // XDesk: a packet missing (over Tailscale or a busy Wi-Fi UDP loses some) breaks every picture after it until
        // the next IDR, and the Mac sends one rarely for a still screen: the picture smeared for a long time. A gap
        // asks for one, and nothing is shown until it comes (ScreenShareSession skips non-IDR pictures meanwhile).
        // An older packet (reordered) is no gap
        if (lastIndex >= 0 && index > lastIndex + 1) {
            gaps++;
            if (!wantKeyframe) Log.i("XDeskScreenShare", "packets missing (" + (index - lastIndex - 1) + "): keyframe asked");
            wantKeyframe = true;
        }
        if (index > lastIndex) lastIndex = index;
        ByteBuffer h = ByteBuffer.wrap(p);
        mediaSsrc = h.getInt(8);
        if (pay.length == 0) return;
        int nal = pay[0] & 31;
        if (hevc == null && (seenTypes & (1 << nal)) == 0) {
            seenTypes |= 1 << nal;
            Log.i("XDeskScreenShare", "RTP payload type=" + (p[1]&127) + " first NAL type=" + nal
                    + " size=" + pay.length + " FU type=" + (nal==28 && pay.length>1 ? pay[1]&31 : -1));
        }
        if (hevc == null && accepted < 5 && nal == 28 && pay.length > 1) Log.i("XDeskScreenShare", "FU flags=" + (pay[1]&255)
                + " marker=" + (p[1] < 0) + " seq=" + (index&65535));
        if (frames == 0 && feedback != null) {
            if (nextRecovery == 0) nextRecovery = ns + 1000000000L;
            if (ns >= nextRecovery) {
                feedback.request(h.getInt(8));
                firs++;
                Log.i("XDeskScreenShare", "FIR sent (no picture yet)");
                nextRecovery = ns + 1000000000L;
            }
        } else if (wantKeyframe && feedback != null && ns >= nextKeyframeAsk) {
            // XDesk: a new decoder (the picture back on a screen) starts at the next IDR: ask for one now
            feedback.request(h.getInt(8));
            firs++;
            Log.i("XDeskScreenShare", "FIR sent (picture shown again, or packets missing)");
            nextKeyframeAsk = ns + 500000000L;
        }
        accepted++;
        haveSsrc = true;
        long ts=Integer.toUnsignedLong(h.getInt(4));
        if(stamp!=ts&&!parts.isEmpty())flush(sink);
        if(parts.isEmpty())firstNs=ns;
        stamp=ts;parts.put(index,pay);
        // The marker ends the access unit, unless a fragment is still open (H.264 FU-A 28, HEVC FU 49 without its E bit)
        boolean complete = hevc != null ? ((pay[0] >> 1) & 63) != 49 || (pay.length >= 3 && (pay[2] & 64) != 0)
                : nal != 28 || (pay.length >= 2 && (pay[1] & 64) != 0);
        if ((p[1] & 128) != 0 && complete) flush(sink);
    }
    void parameters(byte[] p){
        if(p.length<90)return;
        ByteBuffer b=ByteBuffer.wrap(p);
        int end=b.getInt(4)+4;
        if(end>p.length||end<90)return;
        for(int at=90;at+8<=end;){
            int size=b.getInt(at);
            if(size<8||size>end-at)return;
            if(b.getInt(at+4)==0x61766343 && size>=15){
                b.position(at+13);b.limit(at+size);
                for(int type:new int[]{7,8}){
                    if(!b.hasRemaining())return;
                    int count=b.get()&(type==7?31:255);
                    while(count-->0){
                        if(b.remaining()<2)return;
                        int len=word(b);if(len<1||len>b.remaining())return;
                        byte[] unit=new byte[len];b.get(unit);
                        if((unit[0]&31)==type){if(type==7)sps=unit;else pps=unit;}
                    }
                }
                return;
            }
            at+=size;
        }
    }
    void flush(Sink sink)throws Exception{
        if(parts.lastKey()-parts.firstKey()+1!=parts.size()){lost++;parts.clear();wantKeyframe=true;return;}
        if (hevc != null) {
            HevcPicture h = hevc;
            boolean complete = h.assemble(parts.values());
            parts.clear();
            if (!complete) { lost++; wantKeyframe = true; return; }
            if (h.key) { idrs++; wantKeyframe = false; }
            if (h.slice && !wantKeyframe) {
                sink.frame(Arrays.copyOf(h.bytes(), h.size()), firstNs, h.key ? 1 : 0,
                        clock.presentAt(mediaSsrc, (int) stamp, System.nanoTime()));
                frames++;
            }
            return;
        }
        List<byte[]> units=new ArrayList<>();ByteArrayOutputStream fu=null;
        for(byte[] p:parts.values()){
            if(p.length==0)continue;
            if((p[0]&128)!=0 && p.length>=12 && ByteBuffer.wrap(p).getInt(8)==0x61766331){meta++;parameters(p);continue;}
            int t=p[0]&31;
            if(t==24){
                ByteBuffer v=ByteBuffer.wrap(p);v.get();
                while(v.remaining()>=2){int n=word(v);if(n==0||n>v.remaining())break;units.add(bytes(v,n));}
            }else if(t==28&&p.length>=2){
                if((p[1]&128)!=0){fu=new ByteArrayOutputStream();fu.write((p[0]&224)|(p[1]&31));}
                if(fu!=null){fu.write(p,2,p.length-2);if((p[1]&64)!=0){units.add(fu.toByteArray());fu=null;}}
            }else if(t>0&&t<24)units.add(p);
        }
        parts.clear();
        ByteArrayOutputStream pic=new ByteArrayOutputStream();
        boolean idr=false;
        for(byte[] u:units){
            int t=u[0]&31;
            if(t==7){sps=u;continue;}if(t==8){pps=u;continue;}
            if(t==5){idr=true;idrs++;wantKeyframe=false;}
            pic.write(b(0,0,0,1));pic.write(u);
        }
        if(pic.size()>0 && sps!=null && pps!=null && (frames>0||idr)){
            byte[] data=pic.toByteArray();
            if(idr)data=cat(b(0,0,0,1),sps,b(0,0,0,1),pps,data);
            sink.frame(data,firstNs,idr?1:0,clock.presentAt(mediaSsrc,(int)stamp,System.nanoTime()));frames++;
        }
    }
    static final class Incoming {
        final byte[] data; final long time;
        Incoming(byte[] data,long time){this.data=data;this.time=time;}
    }
    static final class Reader implements AutoCloseable {
        final ArrayBlockingQueue<Incoming> packets=new ArrayBlockingQueue<>(4096);
        final MacMediaPort port;
        volatile boolean closed;
        volatile int overflows;
        Reader(MacMediaPort port){
            this.port=port;
            port.receiver=(buffer,length,time)->{
                if(length>=22 && !packets.offer(new Incoming(Arrays.copyOf(buffer,length),time)))overflows++;
            };
        }
        public void close(){closed=true;port.receiver=null;}
    }
    void run(Reader reader,int seconds,Sink sink)throws Exception{
        long end=System.nanoTime()+15_000_000_000L;
        boolean started=false;
        while(!reader.closed && reader.port.available() && (suspended || System.nanoTime()<end)){
            if (suspended) end = Long.MAX_VALUE;
            Incoming p=reader.packets.poll(150,TimeUnit.MILLISECONDS);
            if(p==null)continue;
            packet(p.data,p.data.length,p.time,sink);
            if(!started&&haveSsrc){end=seconds>0?p.time+seconds*1_000_000_000L:Long.MAX_VALUE;started=true;}
        }
        if(!parts.isEmpty())flush(sink);
        Log.i("XDeskScreenShare","DIRECT access_units="+frames+" accepted="+accepted+" incomplete="+lost+" unauthenticated="+stream.rejected
                +" queue_drops="+reader.overflows+" descriptions="+meta+" sps="+(sps!=null)+" pps="+(pps!=null)+" idrs="+idrs+" firs="+firs+" gaps="+gaps);
        if(frames==0)throw new IOException("no decodable direct AVC frames");
    }
}
