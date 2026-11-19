package com.termux.x11;

/**
 * 外屏隐私盖着外屏时，外屏的声音也停（用户 2026-11-19）。XDesk 自己放的声音（Linux 桌面、Mac）在各自的播放线程里
 * 看这个开关，把音量设成 0；放的东西照常往下走，揭开时接着出声，不用重新缓冲。外屏上安卓应用的声音由注入服务
 * 暂时关掉（InjectorService.muteApps）。
 */
public final class SoundCover {
    private static volatile boolean covered;

    private SoundCover() { }

    public static boolean covered() {
        return covered;
    }

    static void set(boolean on) {
        covered = on;
    }
}
