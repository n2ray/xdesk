package com.termux.x11;

/** Only while an external keyboard belongs to a visible Mac Screen Sharing session. */
oneway interface IMacKeyboard {
    void key(String device, int scan, int value);
    void reset();
}
