package com.termux.x11;

import com.termux.x11.IInjector;

/** The process XDesk starts by itself through wireless debugging, see ShellServer. */
interface IShellServer {
    /** The APK install this process was started from. Ids stay fixed: a newer app must be able to ask an older server. */
    int version() = 1;
    /** Ends any earlier session and returns an injector for a new one. */
    IInjector newSession() = 2;
    /** Ends this process. For a server of another version. */
    void exit() = 3;
    /** Ends any earlier wireless session and returns an injector for a new one, next to the app session: see WirelessDisplay. */
    IInjector newWirelessSession() = 4;
}
