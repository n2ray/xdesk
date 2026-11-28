// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import android.content.SharedPreferences;

/** Phone-local shortcut row. A short modifier tap arms one chord, a hold lasts until lift. */
final class PhoneFunctionKeys extends LinearLayout {
    interface Sink { void key(int scan, boolean down); }
    private final FrameLayout root;
    private final View pad,viewport,handle;
    private final Sink sink;
    private final LinearLayout row;
    private final Map<String,TextView> catalog=new LinkedHashMap<>();
    private final List<String> visible=new ArrayList<>();
    private final SharedPreferences preferences;
    private final Map<Integer,Key> keys=new LinkedHashMap<>();
    private boolean side,dark;
    private static final String POSITION_X="function-key-position-x", POSITION_Y="function-key-position-y";
    private float fractionX, fractionY;
    private final int slop;

    PhoneFunctionKeys(FrameLayout root, View pad, View viewport, Sink sink, Consumer<String> insertText, String moveLabel) {
        super(root.getContext());
        this.root=root;this.pad=pad;this.viewport=viewport;this.sink=sink;
        slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(3),dp(2),dp(3),dp(2));
        setElevation(dp(4));setClickable(true);
        TextView grip=new TextView(getContext());
        grip.setText("⠿");grip.setGravity(Gravity.CENTER);grip.setContentDescription(moveLabel);
        handle=grip;
        addView(grip,dp(36),-1);
        grip.setOnTouchListener(new OnTouchListener(){
            float dx,dy;
            boolean moved;
            public boolean onTouch(View v,MotionEvent e) {
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        dx=getX()-e.getRawX();
                        dy=getY()-e.getRawY();
                        moved=false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if(side){
                            View area=floatingArea();
                            int travelX=area.getWidth()-Math.min(dp(336),area.getWidth());
                            int travelY=root.getHeight()-getHeight();
                            if(travelX>0)fractionX=unit((e.getRawX()+dx-area.getLeft())/travelX);
                            if(travelY>0)fractionY=unit((e.getRawY()+dy)/travelY);
                            moved=true;
                            place();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if(moved && side)preferences.edit().putFloat(POSITION_X,fractionX)
                                .putFloat(POSITION_Y,fractionY).apply();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        loadPosition();
                        place();
                        break;
                }
                return true;
            }
        });
        HorizontalScrollView scroll=new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        row=new LinearLayout(getContext());scroll.addView(row);
        addView(scroll,new LayoutParams(0,-1,1));
        int[] scans={29,56,53,105,108,103,106,15,1,28,57,14,111};
        String[] labels={"Ctrl","Alt","/","←","↓","↑","→","Tab","Esc","↵","Space","⌫","Del"};
        for(int i=0;i<scans.length;i++) addKey(scans[i],labels[i]);

        addText("@",insertText);
        addKey(42,"Shift");
        addKey(102,"Home");addKey(107,"End");
        addKey(104,"PgUp");addKey(109,"PgDn");
        for(int i=0;i<10;i++) addKey(59+i,"F"+(i+1));
        addKey(87,"F11");addKey(88,"F12");
        visible.addAll(catalog.keySet());
        addKey(110,"Ins");addKey(58,"Caps");addKey(127,"Menu");
        for(String literal:new String[]{"~","|","\\","-","_","$","#","&","*","[","]","{","}"}) addText(literal,insertText);
        preferences=root.getContext().getSharedPreferences("xdesx-phone",0);
        loadPosition();
        String saved=preferences.getString("function-key-order",null);
        if(saved!=null){
            visible.clear();
            for(String id:saved.split(" ")) if(catalog.containsKey(id) && !visible.contains(id)) visible.add(id);
        }
        rebuildRow();
        root.addView(this,new FrameLayout.LayoutParams(-1,dp(46)));
        setVisibility(GONE);
        View.OnLayoutChangeListener reposition=(v,l,t,r,b,ol,ot,or,ob)->place();
        root.addOnLayoutChangeListener(reposition);
        pad.addOnLayoutChangeListener(reposition);
        viewport.addOnLayoutChangeListener(reposition);
    }
    private void addKey(int scan,String label) {
        Key key=new Key(scan,label);
        keys.put(scan,key);
        catalog.put(label,key);
    }
    private void addText(String literal, Consumer<String> insertText){
        TextView key=new TextView(getContext());
        key.setText(literal);key.setGravity(Gravity.CENTER);key.setTextSize(14);
        key.setFocusable(false);key.setOnClickListener(v -> insertText.accept(literal));
        catalog.put(literal,key);
    }
    List<String> editorKeys(boolean active){
        if(active)return new ArrayList<>(visible);
        List<String> more=new ArrayList<>(catalog.keySet());
        more.removeAll(visible);
        return more;
    }
    void moveKey(String id,boolean active,int slot){
        if(!catalog.containsKey(id))return;
        int previous=visible.indexOf(id);
        if(previous>=0){
            visible.remove(previous);
            if(previous<slot)slot--;
        }
        if(active)visible.add(Math.max(0,Math.min(slot,visible.size())),id);
        preferences.edit().putString("function-key-order",String.join(" ",visible)).apply();
        rebuildRow();
    }
    private void rebuildRow(){
        release();
        row.removeAllViews();
        for(String id:visible)row.addView(catalog.get(id),dp(id.length()>3?57:44),-1);
    }
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    void configure(boolean landscape,boolean night) {
        if(side!=landscape)release();
        side=landscape;dark=night;handle.setVisibility(side?VISIBLE:GONE);
        GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff292b30:0xffeceef2);
        bg.setCornerRadius(dp(side?10:0));setBackground(bg);
        for(TextView key:catalog.values())key.setTextColor(dark?Color.WHITE:Color.BLACK);
        ((TextView)handle).setTextColor(dark?Color.WHITE:Color.DKGRAY);
        for(Key k:keys.values())k.paint();
        place();
    }
    void toggle(){boolean show=getVisibility()!=VISIBLE;release();setVisibility(show?VISIBLE:GONE);if(show)place();}
    private void loadPosition(){
        fractionX=preferences.getFloat(POSITION_X,.5f);
        fractionY=preferences.getFloat(POSITION_Y,Float.NaN);
    }
    private static float unit(float n){return Math.max(0f,Math.min(1f,n));}
    private View floatingArea(){return viewport.getVisibility()==VISIBLE ? viewport : pad;}
    private void place(){
        if(getVisibility()!=VISIBLE || root.getWidth()<=0)return;
        View area=side ? floatingArea() : pad;
        int left=area.getLeft(),w=side?Math.min(dp(336),area.getWidth()):area.getWidth();
        if(w<=0)return;
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)getLayoutParams();
        if(lp.width!=w){lp.width=w;setLayoutParams(lp);}
        if(side){
            int travelY=Math.max(0,root.getHeight()-lp.height);
            float y=Float.isNaN(fractionY)?area.getBottom()-dp(54):fractionY*travelY;
            setX(left+fractionX*(area.getWidth()-w));
            setY(Math.max(0,Math.min(y,travelY)));
        }else{setX(left);setY(pad.getTop());}
    }
    void release(){for(Key k:keys.values()){k.removeCallbacks(k.hold);k.up();k.tracking=false;k.sticky=false;k.paint();}}
    void chordUsed(){for(Key k:keys.values())if(k.sticky){k.sticky=false;k.up();k.paint();}}
    private void promoteModifiers(Key except){
        for(Key k:keys.values())if(k!=except && k.modifier && k.tracking && !k.held){k.removeCallbacks(k.hold);k.startHold();}
    }
    boolean shortcutArmed(){promoteModifiers(null);return keys.get(29).down || keys.get(56).down || keys.get(42).down;}
    private final class Key extends TextView {
        final int scan;final boolean modifier;
        boolean down,sticky,tracking,held;
        float x,y;
        final Runnable hold=this::startHold;
        Key(int scan,String label){super(root.getContext());this.scan=scan;modifier=scan==29||scan==56||scan==42;
            setText(label);setGravity(Gravity.CENTER);setTextSize(14);setClickable(true);setFocusable(false);}
        void paint(){setTextColor(dark?Color.WHITE:Color.BLACK);setBackgroundColor(down?0x665990db:Color.TRANSPARENT);setSelected(down);}
        void press(){if(!down){sink.key(scan,true);down=true;}paint();}
        void up(){if(down){sink.key(scan,false);down=false;}paint();}
        void startHold(){if(tracking){if(!modifier)promoteModifiers(this);held=true;sticky=false;press();getParent().requestDisallowInterceptTouchEvent(true);}}
        @Override public boolean performClick(){
            super.performClick();
            promoteModifiers(this);
            if(modifier){sticky=!sticky;if(sticky)press();else up();}
            else{press();up();chordUsed();}
            return true;
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    tracking=true;held=false;x=e.getX();y=e.getY();postDelayed(hold,ViewConfiguration.getLongPressTimeout());return true;
                case MotionEvent.ACTION_MOVE:
                    if(!held && Math.hypot(e.getX()-x,e.getY()-y)>slop){tracking=false;removeCallbacks(hold);}return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    removeCallbacks(hold);
                    if(held){up();if(!modifier)chordUsed();}
                    else if(tracking && e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
                    tracking=false;held=false;return true;
                default:return true;
            }
        }
    }
}
