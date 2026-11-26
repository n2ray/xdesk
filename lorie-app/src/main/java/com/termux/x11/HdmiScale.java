package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.util.Log;

/** Journal the previous HDMI override before changing it, including across a detach or crash. */
final class HdmiScale {
    private static SharedPreferences journal(Context c) {
        return c.getSharedPreferences("xdesx_hdmi_scale", Context.MODE_PRIVATE);
    }

    static synchronized void enter(Context c,IInjector service,int display,int dpi) throws RemoteException {
        if (display<=0 || dpi<=0) return;
        String id=service.displayUniqueId(display);
        if (id==null || !restore(c,service,display)) return;
        int old=service.xdesxHdmiDensity(display,id,-1,-1);
        int physical=service.displayDefaultDensity(display);
        if (old<=0 || physical<=0 || old==dpi) return;
        SharedPreferences prefs=journal(c);
        if (!prefs.edit().putInt(id,old==physical?0:old).putInt(id+".applied",dpi).commit()) return;
        if (service.xdesxHdmiDensity(display,id,old,dpi)!=dpi) restore(c,service,display);
    }

    static synchronized boolean restore(Context c,IInjector service,int display) {
        if (display<=0) return false;
        SharedPreferences prefs=journal(c);
        if (prefs.getAll().isEmpty()) return true;
        try {
            String id=service.displayUniqueId(display);
            if (id==null) return false;
            if (!prefs.contains(id)) return true;
            int old=prefs.getInt(id,0), applied=prefs.getInt(id+".applied",-1);
            int now=service.xdesxHdmiDensity(display,id,applied,old);
            if (now<=0 || now==applied) return false;
            return prefs.edit().remove(id).remove(id+".applied").commit();
        } catch (RemoteException | RuntimeException e) {
            Log.w("XDesX","HDMI density restore deferred",e);
            return false;
        }
    }
}
