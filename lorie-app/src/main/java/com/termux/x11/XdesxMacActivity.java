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
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(0xfff3f5f4);
        list.setPadding(dp(24),dp(18),dp(24),dp(12));
        setContentView(list);
        render();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView v=new TextView(this);
        v.setText(text); v.setTextColor(color); v.setTextSize(size);
        if(bold) v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        v.setSingleLine();v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return v;
    }
    @Override protected void onResume() {
        super.onResume();
        if(list!=null) render();
    }
    private void render() {
        list.removeAllViews();
        list.addView(label("连接 Mac",24,0xff1c282c,true));
        if(loginPending) {
            list.addView(label("正在外接显示器上登录…",15,0xff52686b,false));
            return;
        }
        GridView grid=new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);grid.setColumnWidth(dp(152));
        grid.setHorizontalSpacing(dp(10));grid.setVerticalSpacing(dp(10));
        grid.setPadding(0,dp(16),0,0);
        java.util.List<ScreenShareStore.Host> devices=ScreenShareStore.all(this);
        grid.setAdapter(new BaseAdapter() {
            public int getCount(){return devices.size()+1;}
            public Object getItem(int i){return i<devices.size()?devices.get(i):null;}
            public long getItemId(int i){return i;}
            public android.view.View getView(int i,android.view.View previous,android.view.ViewGroup parent) {
                ScreenShareStore.Host h=(ScreenShareStore.Host)getItem(i);
                LinearLayout card=new LinearLayout(XdesxMacActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(android.view.Gravity.CENTER);
                card.setPadding(dp(8),0,dp(8),0);
                card.setBackground(Style.rounded(XdesxMacActivity.this,0xffffffff,12));
                card.setLayoutParams(new AbsListView.LayoutParams(-1,dp(138)));
                if(h!=null) {
                    ImageView icon=new ImageView(XdesxMacActivity.this);
                    icon.setImageResource(com.termux.x11.app.R.drawable.xdesx_mac_display);
                    card.addView(icon,new LinearLayout.LayoutParams(dp(72),dp(64)));
                } else {
                    TextView plus=label("+",36,0xff527881,false);
                    plus.setGravity(android.view.Gravity.CENTER);card.addView(plus,new LinearLayout.LayoutParams(-1,dp(64)));
                }
                card.addView(label(h==null?"添加 Mac":h.label(),16,0xff243236,true));
                if(h!=null)card.addView(label(h.account+" @ "+h.host,12,0xff53696b,false));
                return card;
            }
        });
        grid.setOnItemClickListener((parent,view,index,id)->{
            if(index==devices.size()) add(); else open(devices.get(index).id);
        });
        list.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
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
