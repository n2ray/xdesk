package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.termux.x11.screenshare.ScreenSharePasswords;
import com.termux.x11.screenshare.ScreenShareStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Non-secret device settings shared by the panel and connection window. */
final class XdesxMacProfiles implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    XdesxMacProfiles(Context context) throws IOException {
        this.context=context.getApplicationContext();
        directory=new File(context.getFilesDir(),"xd/run");
        directory.mkdirs();
        prefs=context.getSharedPreferences("screenshare",Context.MODE_PRIVATE);
        publish();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }
    void close(){prefs.unregisterOnSharedPreferenceChangeListener(this);}
    public void onSharedPreferenceChanged(SharedPreferences p,String key){
        if("hosts".equals(key)) try {publish();} catch(IOException e){android.util.Log.w("XDesX","publish Mac devices",e);}
    }
    private String field(String s){return s==null?"":s.replaceAll("[\\t\\r\\n]"," ");}
    private void publish() throws IOException {
        StringBuilder rows=new StringBuilder();
        for(ScreenShareStore.Host h:ScreenShareStore.all(context)){
            rows.append(h.id).append('\t').append(field(h.name)).append('\t').append(field(h.host)).append('\t')
                .append(field(h.account)).append('\t').append(h.scale).append('\t').append(h.fixed1080?1:0)
                .append('\t').append(h.measure?1:0).append('\n');
        }
        File tmp=File.createTempFile("devices",".tmp",directory);
        try {
            Files.write(tmp.toPath(),rows.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(),new File(directory,"mac-hosts").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {tmp.delete();}
    }
    void apply(String command) {
        if(command.startsWith("mac-remove-")){
            String id=command.substring(11);
            ScreenShareSource source=ScreenShareSource.get();
            if(source!=null)source.disconnect(id);
            ScreenSharePasswords.forget(context,id);
            ScreenShareStore.remove(context,id);
            return;
        }
        String raw=new String(Base64.decode(command.substring(9),Base64.DEFAULT),StandardCharsets.UTF_8);
        String[] v=raw.split("\\t",-1);
        if(v.length!=7 || !v[0].matches("new|[0-9a-f]{8}") || v[2].trim().isEmpty() || v[3].trim().isEmpty() ||
            !v[4].matches("[012]") || !v[5].matches("[01]") || !v[6].matches("[01]"))
            throw new IllegalArgumentException(context.getString(com.termux.x11.app.R.string.xu_check_the_device_address_and_account));
        String id=v[0].equals("new")?null:v[0];
        if(id!=null){
            ScreenShareStore.Host old=ScreenShareStore.find(context,id);
            if(old==null) throw new IllegalArgumentException(context.getString(com.termux.x11.app.R.string.xu_the_device_has_been_removed));
            if(!old.host.equals(v[2]) || !old.account.equals(v[3])) ScreenSharePasswords.forget(context,id);
        }
        ScreenShareStore.save(context,id,v[1].trim(),v[2].trim(),v[3].trim(),v[5].equals("1"),v[6].equals("1"),Integer.parseInt(v[4]));
    }
}
