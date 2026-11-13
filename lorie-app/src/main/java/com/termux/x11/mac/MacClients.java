package com.termux.x11.mac;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** One MacClient per saved Mac, for the whole process. Created lazily; nothing connects until asked. */
public final class MacClients {
    private static final LinkedHashMap<String, MacClient> clients = new LinkedHashMap<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    private MacClients() { }

    /** The clients of the saved Macs, in the order they were paired. Syncs with MacStore. */
    public static synchronized List<MacClient> all(Context c) {
        List<MacStore.Mac> saved = MacStore.all(c);
        LinkedHashMap<String, MacClient> next = new LinkedHashMap<>();
        for (MacStore.Mac m : saved) {
            MacClient old = clients.remove(m.id);
            if (old != null) {
                old.updateMac(m);
                next.put(m.id, old);
            } else {
                next.put(m.id, new MacClient(c, m));
            }
        }
        for (MacClient gone : clients.values()) gone.shutdown();
        clients.clear();
        clients.putAll(next);
        return new ArrayList<>(clients.values());
    }

    public static synchronized MacClient get(Context c, String id) {
        if (!clients.containsKey(id)) all(c);
        return clients.get(id);
    }

    /** The list of Macs changed (paired, removed, options). Listeners run on the caller's thread. */
    public static void savedChanged(Context c) {
        all(c);
        List<Runnable> ls;
        synchronized (MacClients.class) {
            ls = new ArrayList<>(listeners);
        }
        for (Runnable r : ls) r.run();
    }

    public static synchronized void addListener(Runnable r) {
        listeners.add(r);
    }
}
