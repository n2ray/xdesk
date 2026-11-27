// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.*;
import com.termux.x11.screenshare.*;

/** Root of the Mac window task. The picture joins this task so both move together. */
public final class XdesxMacActivity extends Activity implements ScreenShareSource.WindowResult {
    private static final java.util.Map<String,XdesxMacActivity> owners=new java.util.HashMap<>();
    private String key,host;
    private boolean pending;
    static void closeWindow(String key) {
        new Handler(Looper.getMainLooper()).post(()->{
            XdesxMacActivity owner=owners.get(key);
            if(owner!=null){owner.disconnect();owner.finishAndRemoveTask();}
        });
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        key=getIntent().getStringExtra("window");
        host=getIntent().getStringExtra("host");
        if(key==null || host==null || ScreenShareStore.find(this,host)==null){finish();return;}
        owners.put(key,this);
        ScreenShareSource.start(this);
        Button connect=new Button(this);
        connect.setText(com.termux.x11.app.R.string.xd_ss_connect);
        android.widget.FrameLayout page=new android.widget.FrameLayout(this);
        page.addView(connect,new android.widget.FrameLayout.LayoutParams(-2,-2,android.view.Gravity.CENTER));
        setContentView(page);
        connect.setOnClickListener(v->open());
        open();
    }
    private void open(){
        if(pending || isFinishing())return;
        pending=true;
        ScreenShareSource.get().open(ScreenShareSource.key(host),getDisplay().getDisplayId(),this);
    }
    public void showSession(String id) {
        if (isFinishing() || isDestroyed() || !id.equals(host)) return;

        pending=false;
        startActivity(new Intent(this,ScreenShareActivity.class)
            .putExtra(ScreenShareActivity.EXTRA_HOST,id).putExtra("xdesx_input",true)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
    public void shown(String key, InputSink input) { }
    public void failed(String key,String reason) {
        if (isDestroyed() || isFinishing()) return;
        finishAndRemoveTask();
        Toast.makeText(this,reason,Toast.LENGTH_LONG).show();
    }
    private void disconnect() {
        if (host==null) return;
        ScreenShareSource source=ScreenShareSource.get();
        source.closeWindowLogin(host,this);
        source.disconnect(host);
        host=null;
    }
    @Override protected void onDestroy() {
        if (owners.remove(key,this))disconnect();
        super.onDestroy();
    }
}
