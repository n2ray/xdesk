// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.*;
import android.widget.*;
import com.termux.x11.screenshare.*;

/** Root of the Mac window task. The picture joins this task so both move together. */
public final class XdesxMacActivity extends Activity implements ScreenShareSource.WindowResult {
    private static XdesxMacActivity owner;
    private String key,host;
    private boolean loginPending;
    private LinearLayout list;

    static void closeWindow(String key) {
        new Handler(Looper.getMainLooper()).post(() -> {
            XdesxMacActivity a=owner;
            if (a!=null && key.equals(a.key)) {
                a.disconnect();
                a.finishAndRemoveTask();
            }
        });
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        key=getIntent().getStringExtra("window");
        if (key==null) { finish(); return; }
        owner=this;
        ScreenShareSource.start(this);
        ScrollView scroll=new ScrollView(this);
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        list.setPadding(pad,pad,pad,pad);
        scroll.addView(list);
        setContentView(scroll);
        render();
    }
    private void render() {
        list.removeAllViews();
        TextView title=new TextView(this);
        title.setText(loginPending?"请在手机上完成 Mac 登录":"Mac 高性能屏幕共享");
        title.setTextSize(20);
        list.addView(title);
        if (loginPending) return;
        for (ScreenShareStore.Host h:ScreenShareStore.all(this)) {
            button(h.label(),() -> open(h.id));
            button("管理 "+h.label(),() -> ScreenShareSettings.edit(this,h.id));
        }
        button("添加 Mac",this::add);
    }
    private void button(String label,Runnable action) {
        Button b=new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        list.addView(b);
    }
    private void add() {
        LinearLayout form=new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText address=new EditText(this),account=new EditText(this);
        address.setSingleLine(); account.setSingleLine();
        address.setHint("Mac 地址"); account.setHint("Mac 账户名");
        form.addView(address); form.addView(account);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加 Mac").setView(form)
            .setNegativeButton("取消",null).setPositiveButton("连接",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            String hostname=address.getText().toString().trim(), user=account.getText().toString().trim();
            if (hostname.isEmpty()) { address.setError("请输入地址"); return; }
            if (user.isEmpty()) { account.setError("请输入账户名"); return; }
            String id=ScreenShareStore.save(this,null,hostname,hostname,user,false,false,ScreenShareSizing.NATIVE);
            dialog.dismiss(); open(id);
        }));
        dialog.show();
    }
    private void open(String id) {
        if (loginPending) return;
        if (host!=null && !host.equals(id)) disconnect();
        host=id; loginPending=true; render();
        ScreenShareSource.get().open(ScreenShareSource.key(id),getDisplay().getDisplayId(),this);
    }
    public void showSession(String id) {
        if (isFinishing() || isDestroyed() || !id.equals(host)) return;
        loginPending=false;
        render();
        startActivity(new Intent(this,ScreenShareActivity.class)
            .putExtra(ScreenShareActivity.EXTRA_HOST,id).putExtra("xdesx_input",true)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
    public void shown(String key, InputSink input) { }
    public void failed(String key,String reason) {
        if (isDestroyed() || isFinishing()) return;
        loginPending=false; render();
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
        if (owner==this) { owner=null; disconnect(); }
        super.onDestroy();
    }
}
