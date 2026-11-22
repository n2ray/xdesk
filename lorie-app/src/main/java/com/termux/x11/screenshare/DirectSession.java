package com.termux.x11.screenshare;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;

import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

final class DirectSession implements Closeable {
    final AssetManager assets;
    final InetAddress host;
    final SecureRandom rng=new SecureRandom();
    final Socket control=new Socket();
    final DatagramSocket video, audio;
    private final AudioReceiver soundReceiver;
    final MediaClock clock = new MediaClock();
    DataInputStream in;
    OutputStream out;
    ControlWriter records;
    final int width,height;
    Cipher pointerCipher;
    KeyframeFeedback feedback;
    private final ScheduledExecutorService probes = Executors.newSingleThreadScheduledExecutor();
    void pointer(int mask,int x,int y)throws Exception {
        byte[] event=((ByteBuffer)ByteBuffer.allocate(16).position(10)).put((byte)255).put((byte)mask).putShort((short)x).putShort((short)y).array();
        encrypted(cat(b(16,0),pointerCipher.doFinal(event)));
    }
    void key(boolean down,int sym)throws Exception {
        byte[] event=ByteBuffer.allocate(16).put((byte)255).put((byte)(down?1:0)).putInt(sym).array();
        encrypted(cat(b(16,0),pointerCipher.doFinal(event)));
    }
    byte[] displayConfig()throws Exception {
        // One virtual display, one 1x mode. DisplayInfo starts at byte 12.
        byte[] d=Arrays.copyOf(asset("display"),12+156+28);
        ByteBuffer buf=ByteBuffer.wrap(d);buf.putShort(2,(short)(d.length-4));
        buf.putShort(12,(short)(d.length-12));buf.putShort(12+154,(short)1);
        buf.putInt(12+138,width);buf.putInt(12+142,height);
        buf.position(12+156);buf.putInt(width).putInt(height).putInt(width).putInt(height);
        return d;
    }
    DirectSession(android.content.Context context,String h,int w,int sizeY)throws Exception {
        assets=context.getAssets();width=w;height=sizeY; host=InetAddress.getByName(h);
        audio=new DatagramSocket(5900);
        try {video=new DatagramSocket(5901);}catch(Exception e){audio.close();throw e;}
        video.setReceiveBufferSize(4<<20); video.setSoTimeout(300);
        soundReceiver = new AudioReceiver(context, audio, host, clock);
    }
    public void close(){probes.shutdownNow();audio.close();soundReceiver.close();video.close();try{control.close();}catch(IOException ignored){}}
    byte[] asset(String n)throws IOException{try(InputStream s=assets.open("screenshare/"+n)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[1024];int len;while((len=s.read(buf))!=-1)b.write(buf,0,len);return b.toByteArray();}}
    static byte[] cat(byte[]... parts){ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] p:parts)o.write(p,0,p.length);return o.toByteArray();}
    static byte[] b(int... v){byte[] a=new byte[v.length];for(int i=0;i<v.length;i++)a[i]=(byte)v[i];return a;}
    static byte[] str(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static byte[] u16(int i){return b(i>>>8,i);}
    static byte[] u32(int i){return ByteBuffer.allocate(4).putInt(i).array();}
    byte[] random(int n){byte[] v=new byte[n];rng.nextBytes(v);return v;}
    static byte[] hash(String type,byte[]... v)throws Exception{return MessageDigest.getInstance(type).digest(cat(v));}
    static byte[] sha(byte[]... v)throws Exception{return hash("SHA-512",v);}
    static byte[] bytes(ByteBuffer buf,int n)throws IOException{
        if(n<0||n>buf.remaining())throw new IOException("truncated protocol value");
        byte[] a=new byte[n];buf.get(a);return a;
    }
    static int word(ByteBuffer buf){return buf.getShort()&65535;}
    static byte[] read(DataInputStream input,int n)throws IOException{
        if(n<0||n>1<<20)throw new IOException("invalid control length");byte[] v=new byte[n];input.readFully(v);return v;
    }
    byte[] packet()throws IOException{return read(in,in.readInt());}
    static BigInteger num(byte[] b){return new BigInteger(1,b);}
    static byte[] pad(BigInteger n){byte[] v=n.toByteArray(),buf=new byte[512];int l=Math.min(512,v.length);System.arraycopy(v,v.length-l,buf,512-l,l);return buf;}
    static Cipher aes(String mode,int op,byte[] key,byte[] iv)throws Exception{
        Cipher c=Cipher.getInstance("AES/"+mode+"/NoPadding");
        c.init(op,new SecretKeySpec(key,"AES"),iv==null?null:new IvParameterSpec(iv));return c;
    }
    void banner(Socket s)throws Exception{
        DataInputStream d=new DataInputStream(s.getInputStream());byte[] version=read(d,12);
        if(!new String(version,StandardCharsets.US_ASCII).startsWith("RFB "))throw new IOException("invalid RFB banner");
        s.getOutputStream().write(str("RFB 003.889\n"));
        byte[] types=read(d,d.readUnsignedByte());for(byte t:types)if(t==33)return;
        throw new IOException("Mac did not offer SRP/RSA1");
    }
    void encrypted(byte[] msg)throws Exception {
        records.send(msg);
    }
    /** M4 single-display session. The first connection wakes the system sharing service. */
    byte[] start(String username, char[] password) throws Exception {
        InetSocketAddress server = new InetSocketAddress(host, 5900);
        try (Socket wake = new Socket()) {
            wake.connect(server, 5000);
            wake.setSoTimeout(5000);
            banner(wake);
        }
        Thread.sleep(1400);
        control.connect(server, 5000);
        control.setTcpNoDelay(true);
        control.setSoTimeout(15000);
        in = new DataInputStream(new BufferedInputStream(control.getInputStream()));
        out = control.getOutputStream();
        banner(control);
        byte[] wrap = new AppleLogin(this).authenticate(username, password);
        Log.i("XDeskScreenShare", "SRP verified, direct TCP "+control.getLocalAddress()+" -> "+server);
        beginDesktop();
        enableRecords(wrap);
        Arrays.fill(wrap, (byte)0);
        startMediaPorts();
        byte[] receiveKey = random(46);
        sendMediaConfiguration(receiveKey);
        ByteBuffer update = ByteBuffer.allocate(10);
        update.put((byte)3).position(6);
        update.putShort((short)0xffff).putShort((short)0xffff);
        records.send(update.array());
        control.setSoTimeout(0);
        Thread monitor = new Thread(this::monitorControl, "screen-sharing-control");
        monitor.setDaemon(true);
        monitor.start();
        return receiveKey;
    }

    // These controls run on ScreenShareSession's serial input executor.
    void hidePicture(AvcReceiver receiver) {
        // The same-size display reconfiguration stops audio as well as video on M4.
        if (receiver != null) receiver.hide();
    }

    void showPicture(AvcReceiver receiver) throws Exception {
        if (receiver == null) return;
        receiver.suspended = false;
        receiver.wantKeyframe = true;
        requestFullUpdate();
    }

    /** XDesk: a non-incremental framebuffer update request, as start() sends (a fallback when no IDR comes). */
    void requestFullUpdate() throws Exception {
        ByteBuffer update = ByteBuffer.allocate(10);
        update.put((byte)3);
        update.position(6);
        update.putShort((short)0xffff).putShort((short)0xffff);
        records.send(update.array());
    }

    private void beginDesktop() throws Exception {
        out.write(0xc1);
        read(in, 20); // dimensions and pixel format
        read(in, in.readInt()); // desktop name
        out.write(asset("viewer"));
        out.write(displayConfig());
        out.write(asset("encodings"));
    }

    private void enableRecords(byte[] wrap) throws Exception {
        if (in.readUnsignedShort() != 0) throw new IOException("expected initial framebuffer update");
        int rectangles = in.readUnsignedShort();
        for (int i = 0; i < rectangles; i++) {
            read(in, 8);
            int kind = in.readInt();
            if (kind == 1010 || kind == 1011) {
                read(in, in.readUnsignedShort());
                continue;
            }
            if (kind != 1103) throw new IOException("unexpected initial encoding "+kind);
            read(in, 4);
            byte[] material = aes("ECB", Cipher.DECRYPT_MODE, wrap, null).doFinal(read(in, 32));
            records = new ControlWriter(out, material);
            pointerCipher = aes("ECB", Cipher.ENCRYPT_MODE, Arrays.copyOf(material,16), null);
            Arrays.fill(material, (byte)0);
            ByteBuffer toggle = ByteBuffer.allocate(8);
            toggle.putShort((short)0x1200).putShort((short)2).putInt(0x00010000);
            out.write(toggle.array());
            records.send(asset("encodings"));
            return;
        }
        throw new IOException("server did not advertise a record key");
    }

    private void startMediaPorts() throws InterruptedException {
        DatagramPacket a = new DatagramPacket(new byte[1], 1, host, 5900);
        DatagramPacket v = new DatagramPacket(new byte[1], 1, host, 5901);
        probes.scheduleWithFixedDelay(() -> {
            try {
                audio.send(a);
                video.send(v);
            } catch (IOException error) {
                if (!control.isClosed()) close();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        // Allow avconferenced to open its UDP endpoints before advertising the streams.
        Thread.sleep(5000);
    }

    // Single-display form of Rootshell TransportSession's MIT media configuration.
    private void sendMediaConfiguration(byte[] receiveKey) throws Exception {
        MediaOffer profile = new MediaOffer(asset("media-profile.json"));
        byte[] sound = profile.create(false, Integer.toUnsignedLong(rng.nextInt()), width, height);
        int viewerSsrc = rng.nextInt();
        byte[] sendKey = random(46);
        byte[] picture = profile.create(true, Integer.toUnsignedLong(viewerSsrc), width, height);
        feedback = new KeyframeFeedback(sendKey, viewerSsrc, video, host);
        int length = 0xdc + sound.length + picture.length;
        if (length > 65535) throw new IOException("media configuration too large");
        ByteBuffer config = ByteBuffer.allocate(length);
        config.put((byte)0x1c).put((byte)0).putShort((short)(length - 4));
        config.putShort((short)3).putInt(7);
        config.putShort((short)sound.length).putShort((short)picture.length);
        config.position(0x14);
        UUID id = UUID.randomUUID();
        config.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        byte[] audioReceiveKey = random(46);
        config.put(random(46)).put(audioReceiveKey).put(sound);
        config.put(sendKey).put(receiveKey).put(picture);
        try {
            records.send(config.array());
            soundReceiver.setKey(audioReceiveKey);
        } finally {
            Arrays.fill(audioReceiveKey, (byte) 0);
        }
    }

    private void monitorControl() {
        byte[] buffer = new byte[8192];
        try {
            while (in.read(buffer) >= 0) { /* No negotiated clipboard or cursor consumer. */ }
        } catch (IOException closed) {
            // Closing the session also releases a blocked reader.
        } finally {
            close();
        }
    }
}
