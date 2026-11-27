// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Apple sends media to fixed local ports; the sending Mac, not its UDP source port,
 * identifies the session. Keep the UDP reader and kernel buffer shared until the last peer leaves. */
final class MacMediaPort implements AutoCloseable {
    interface Receiver { void packet(byte[] buffer,int length,long time); }
    private static final HashMap<Integer,Hub> ports=new HashMap<>();
    private final Hub hub;
    private final InetAddress host;
    volatile Receiver receiver;
    boolean available(){return !hub.socket.isClosed() && hub.peers.get(host)==this;}

    private MacMediaPort(Hub hub,InetAddress host){this.hub=hub;this.host=host;}
    static synchronized MacMediaPort open(int port,InetAddress host)throws IOException {
        Hub hub=ports.get(port);
        if(hub==null){hub=new Hub(port);ports.put(port,hub);}
        if(hub.peers.containsKey(host))throw new IOException("This Mac already has an active sharing session");
        MacMediaPort peer=new MacMediaPort(hub,host);
        hub.peers.put(host,peer);
        return peer;
    }
    void send(DatagramPacket p)throws IOException {
        if(!available() || !host.equals(p.getAddress()))throw new SocketException("Mac media route closed");
        hub.socket.send(p);
    }
    public void close(){
        synchronized(MacMediaPort.class){
            receiver=null;
            if(hub.peers.remove(host,this) && hub.peers.isEmpty()){
                ports.remove(hub.port,hub);
                hub.socket.close();
            }
        }
    }
    private static final class Hub {
        final int port;
        final DatagramSocket socket;
        final ConcurrentHashMap<InetAddress,MacMediaPort> peers=new ConcurrentHashMap<>();
        Hub(int port)throws IOException{
            this.port=port;
            socket=new DatagramSocket(port);
            try {socket.setReceiveBufferSize(4<<20);}
            catch(SocketException e){socket.close();throw e;}
            Thread reader=new Thread(this::read,"mac-media-"+port);
            reader.setDaemon(true);reader.start();
        }
        void read(){
            byte[] buffer=new byte[65536];
            DatagramPacket p=new DatagramPacket(buffer,buffer.length);
            try{
                while(!socket.isClosed()){
                    p.setLength(buffer.length);socket.receive(p);
                    MacMediaPort peer=peers.get(p.getAddress());
                    Receiver target=peer==null?null:peer.receiver;
                    if(target!=null)target.packet(buffer,p.getLength(),System.nanoTime());
                }
            } catch(IOException stopped){socket.close();}
        }
    }
}
