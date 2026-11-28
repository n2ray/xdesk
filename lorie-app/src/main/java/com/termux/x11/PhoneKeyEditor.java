// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Both grids stay on screen, including when one of them is empty. */
final class PhoneKeyEditor {
    private final Context context;
    private final PhoneFunctionKeys keys;
    private final int ink, card, accent;
    private final Section active, available;
    private String dragged;

    PhoneKeyEditor(Context context, PhoneFunctionKeys keys, LinearLayout parent,
                  String activeTitle,String availableTitle,int ink,int card,boolean dark) {
        this.context=context;
        this.keys=keys;
        this.ink=ink;
        this.card=card;
        accent=dark?0xff97b9ff:0xff365da0;
        active=new Section(parent,activeTitle,true);
        available=new Section(parent,availableTitle,false);
    }

    private int dp(int value){return Math.round(context.getResources().getDisplayMetrics().density*value);}

    private void move(String id,boolean show,int slot){
        keys.moveKey(id,show,slot);
        active.refresh();
        available.refresh();
    }

    private final class Section extends BaseAdapter implements View.OnDragListener {
        final boolean show;
        final GridView grid;
        List<String> items;
        int scrollStep, marker=-1;
        boolean markerAfter;
        float lastX,lastY;
        final Runnable scroll=new Runnable(){
            public void run(){
                if(scrollStep==0 || !grid.isAttachedToWindow() || !grid.canScrollVertically(scrollStep))return;
                grid.scrollListBy(scrollStep);
                locate(lastX,lastY);
                grid.postOnAnimation(this);
            }
        };

        Section(LinearLayout parent,String title,boolean show){
            this.show=show;
            items=keys.editorKeys(show);
            TextView caption=new TextView(context);
            caption.setText(title);
            caption.setTextColor(ink);
            caption.setTextSize(15);
            caption.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            caption.setAccessibilityHeading(true);
            caption.setPadding(0,dp(6),0,dp(4));
            parent.addView(caption);
            grid=new GridView(context);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(dp(56));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(4));
            grid.setVerticalSpacing(dp(4));
            GradientDrawable well=new GradientDrawable();
            well.setColor(0x106c8099);
            well.setCornerRadius(dp(10));
            grid.setBackground(well);
            grid.setPadding(dp(4),dp(4),dp(4),dp(4));
            grid.setContentDescription(title);
            grid.setAdapter(this);
            parent.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
            grid.setOnItemClickListener((p,v,i,id)->move(getItem(i),!show,active.getCount()));
            grid.setOnItemLongClickListener((p,v,i,id)->{
                dragged=getItem(i);
                if(!v.startDragAndDrop(null,new View.DragShadowBuilder(v),PhoneKeyEditor.this,0))dragged=null;
                return true;
            });
            grid.setOnDragListener(this);
        }

        void refresh(){items=keys.editorKeys(show);notifyDataSetChanged();}
        public int getCount(){return items.size();}
        public String getItem(int position){return items.get(position);}
        public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            TextView tile=(TextView)recycled;
            if(tile==null){
                tile=new TextView(context);
                tile.setLayoutParams(new AbsListView.LayoutParams(-1,dp(48)));
                tile.setGravity(Gravity.CENTER);
                tile.setTextSize(16);
                tile.setTextColor(ink);
            }
            tile.setText(getItem(position));
            paint(tile,position==marker);
            return tile;
        }

        private void paint(View tile,boolean marked){
            GradientDrawable bg=new GradientDrawable();
            bg.setCornerRadius(dp(9));
            bg.setColor(card);
            tile.setBackground(bg);
            if(!marked){tile.setForeground(null);return;}
            GradientDrawable edge=new GradientDrawable();
            edge.setColor(accent);
            android.graphics.drawable.LayerDrawable overlay=new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{edge});
            overlay.setLayerWidth(0,dp(3));
            overlay.setLayerGravity(0,(markerAfter?Gravity.END:Gravity.START)|Gravity.FILL_VERTICAL);
            tile.setForeground(overlay);
        }

        private void mark(int position,boolean after){
            if(position==marker && after==markerAfter)return;
            markerAfter=after;
            int first=grid.getFirstVisiblePosition();
            View old=grid.getChildAt(marker-first);
            if(old!=null)paint(old,false);
            marker=position;
            View next=grid.getChildAt(position-first);
            if(next!=null)paint(next,true);
        }

        // Resolve gaps and partial last rows as well as drops directly over keys.
        private int locate(float x,float y){
            int first=grid.getFirstVisiblePosition();
            boolean rtl=grid.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            for(int i=0;i<grid.getChildCount();i++){
                View v=grid.getChildAt(i);
                if(y>v.getBottom()+grid.getVerticalSpacing()/2f)continue;
                boolean lastInRow=i+1==grid.getChildCount() || grid.getChildAt(i+1).getTop()!=v.getTop();
                if(!lastInRow && (rtl?x<v.getLeft():x>v.getRight()))continue;
                boolean after=rtl?x<v.getLeft()+v.getWidth()/2f:x>v.getLeft()+v.getWidth()/2f;
                mark(first+i,after);
                return first+i+(after?1:0);
            }
            mark(-1,false);
            return getCount();
        }

        private void stopScroll(){scrollStep=0;grid.removeCallbacks(scroll);}

        public boolean onDrag(View v,DragEvent event){
            if(event.getLocalState()!=PhoneKeyEditor.this)return false;
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_LOCATION:
                    lastX=event.getX();lastY=event.getY();
                    if(show)locate(lastX,lastY);
                    int step=lastY<dp(30)?-dp(5):lastY>grid.getHeight()-dp(30)?dp(5):0;
                    if(step!=scrollStep){
                        stopScroll();
                        scrollStep=step;
                        if(step!=0)grid.postOnAnimation(scroll);
                    }
                    break;
                case DragEvent.ACTION_DROP:
                    if(dragged!=null){
                        int slot=show?locate(event.getX(),event.getY()):0;
                        stopScroll();mark(-1,false);
                        move(dragged,show,slot);
                    }
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    stopScroll();mark(-1,false);
                    break;
                default:break;
            }
            return true;
        }
    }
}
