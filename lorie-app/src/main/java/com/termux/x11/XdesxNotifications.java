package com.termux.x11;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.UUID;

/** Only a change counter and bell preferences go to disk. Panel rows travel over the private menu socket. */
final class XdesxNotifications implements AutoCloseable {
    private final Context context;
    private final NotifyCenter center;
    private final File state;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable update=this::refresh;
    private final Runnable changed=()->{main.removeCallbacks(update);main.post(update);};
    private final HashMap<String,String> keys=new HashMap<>();
    private volatile byte[] rows={};
    private String last="";
    XdesxNotifications(Context context){
        this.context=context;
        center=NotifyCenter.get(context);
        state=new File(context.getFilesDir(),"xd/run/notify-state");
        center.addListener(changed);
        refresh();
    }
    private static String clean(String text){
        if(text==null)return "";
        return text.substring(0,Math.min(400,text.length())).replaceAll("[\\p{Cntrl}]"," ");
    }
    byte[] rows(){return rows;}
    void refresh(){
        boolean allowed=NotifyCenter.accessGranted(context);
        boolean ready=allowed && center.connected();
        boolean names=center.namesOnly();
        String status=(allowed?1:0)+" "+(ready?1:0)+" "+(names?1:0)+" "+(ready?center.unread():0)+" "+center.listVersion();
        if(status.equals(last))return;
        StringBuilder list=new StringBuilder();
        keys.clear();
        if(ready) for(NotifyCenter.Group group:center.groups()){
            for(NotifyCenter.Entry entry:group.entries){
                String id=UUID.nameUUIDFromBytes(entry.key.getBytes(StandardCharsets.UTF_8)).toString();
                keys.put(id,entry.key);
                list.append(clean(group.pkg)).append('\t').append(clean(group.appLabel)).append('\t').append(id).append('\t')
                    .append(entry.sbn.isClearable()?1:0).append('\t').append(names?"":clean(entry.title)).append('\t')
                    .append(names?"":clean(entry.text)).append('\n');
            }
        }
        rows=list.toString().getBytes(StandardCharsets.UTF_8);
        try{
            File dir=state.getParentFile();dir.mkdirs();
            File tmp=new File(dir,"notify-state.tmp");
            Files.write(tmp.toPath(),status.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(),state.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            last=status;
        }catch(IOException e){Log.w("XDesX","notification indicator",e);}
    }
    void action(String op){
        switch(op){
            case "access": NotifyCenter.openAccessSettings(context);return;
            case "seen": if(center.unread()>0)center.markSeen();return;
            case "clear": center.clearAll();return;
            case "names": center.setBannerContent(NotifyCenter.CONTENT_NAME);return;
            case "summary": center.setBannerContent(NotifyCenter.CONTENT_ALL);return;
            case "rebind": center.rebindIfNeeded();return;
            default: String key=keys.get(op);if(key!=null)center.cancel(key);
        }
    }
    public void close(){center.removeListener(changed);main.removeCallbacks(update);rows=new byte[0];keys.clear();}
}
