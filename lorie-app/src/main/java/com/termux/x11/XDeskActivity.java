package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 打开即启动桌面并放到外屏，手机屏做触摸板和键盘。流程见 PLAN.md。 */
public class XDeskActivity extends Activity {
    private static final String TAG = "XDesk";
    private static final String PERM = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_RUN_COMMAND = 1, REQ_NOTIFICATIONS = 2;
    private static final String PKG_TERMUX = "com.termux";

    private static final String PREF_OUTPUT = "audio_output", PREF_WIDTH = "desktop_width", PREF_SPEED = "pad_speed",
        PREF_DIM = "dim_idle", PREF_DIAG = "show_diag", PREF_ORIENTATION = "orientation";
    private static final String[] OUTPUT_LABELS = {"手机扬声器", "外接屏（HDMI）", "系统默认（蓝牙等）"};
    private static final String[] OUTPUT_SHORT = {"手机", "外接", "系统"};
    private static final String[] WIDTH_LABELS = {"清晰（显示器原生）", "均衡（1920 宽）", "省电和游戏（1280 宽）"};
    private static final int[] WIDTHS = {0, 1920, 1280};  // 0 = 原生
    private static final int DEFAULT_WIDTH = 1920;
    private static final String[] ORIENTATION_LABELS = {"横屏", "竖屏", "跟随系统"};
    private static final int[] ORIENTATIONS = {ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_FULL_USER};

    private static final long CONNECT_TIMEOUT_MS = 40000;
    private static final long RESTART_NOTICE_MS = 30000;
    private static final long RECAPTURE_DELAY_MS = 500;
    private static final long REFOCUS_DELAY_MS = 800;
    private static final long DIM_AFTER_MS = 60000;
    private static final float DIM_BRIGHTNESS = 0.01f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private InputManager inputs;
    private InputMethodManager ime;
    private SharedPreferences prefs;

    private FrameLayout screens;
    private View runningScreen, setupScreen, topBar;
    private int lastOrientation;
    private TextView setupTitle, setupMessage, setupAction;
    private ProgressBar setupProgress;
    private TextView statusText, diagText, audioChip;
    private View statusDot;
    private TouchpadView touchpad;
    private KeyInputView keyView;

    private DesktopInput client;
    private AudioPlayer audio;
    private DesktopSession session;
    private boolean captured, dimmed, resumed;
    /** 桌面窗口离开了外屏，还没放回去。App 在后台时系统可能拦下放置，回到前台再补。 */
    private boolean needsPlacement;
    private int keyEvents, mouseEvents;
    private float mouseRestX, mouseRestY, wheelRestV, wheelRestH;
    private final List<Latch> latches = new ArrayList<Latch>();

    private static final class Latch {
        final int scan;
        final TextView button;
        boolean on;
        Latch(int scan, TextView button) { this.scan = scan; this.button = button; }
    }

    private final TouchpadView.Output output = new TouchpadView.Output() {
        @Override public void move(int dx, int dy) { if (client != null) client.move(dx, dy); }
        @Override public void button(int b, boolean down) { if (client != null) client.button(b, down); }
        @Override public void click(int b) { if (client != null) client.click(b); }
        @Override public void scroll(int dy, int dx) { if (client != null) client.scroll(dy, dx); }
    };

    private final KeyInputView.Sink keySink = new KeyInputView.Sink() {
        @Override public void text(String s) {
            if (client == null) return;
            client.text(s);
            releaseLatches();
        }
        @Override public void tapKey(int scan) {
            if (client == null) return;
            client.tapKey(scan);
            releaseLatches();
        }
        @Override public void mouseMove(float dx, float dy) {
            mouseEvents++;
            updateDiag();
            if (client == null) return;
            mouseRestX += dx;
            mouseRestY += dy;
            int x = (int) mouseRestX, y = (int) mouseRestY;
            if (x != 0 || y != 0) {
                mouseRestX -= x;
                mouseRestY -= y;
                client.move(x, y);
            }
        }
        @Override public void mouseButton(int b, boolean down) {
            mouseEvents++;
            updateDiag();
            if (client != null) client.button(b, down);
        }
        @Override public void captureChanged(boolean has) {
            captured = has;
            updateDiag();
            // 系统在转屏等时候会收回抓取；窗口还有焦点、鼠标还在就要回来，否则光标会跑到手机屏上
            if (!has) {
                ui.removeCallbacks(recapture);
                ui.postDelayed(recapture, RECAPTURE_DELAY_MS);
            }
        }
        @Override public void mouseScroll(float v, float h) {
            if (client == null) return;
            wheelRestV += v;
            wheelRestH += h;
            int tv = (int) wheelRestV, th = (int) wheelRestH;
            if (tv != 0 || th != 0) {
                wheelRestV -= tv;
                wheelRestH -= th;
                client.scroll(-tv, th);
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {
            if (!session.isActive()) {
                tryStart();
                return;
            }
            // 可能换了显示器：按新尺寸算分辨率，再把桌面放回去
            Display d = displays.getDisplay(id);
            if (d != null) applySize(d);
            placeX11(id);
            needsPlacement = !resumed;
            updateStatus();
        }
        @Override public void onDisplayRemoved(int id) {
            if (!session.isActive()) {
                tryStart();
                return;
            }
            setStatus(Style.WARN, "外接屏已断开，桌面还在运行，插回后自动恢复");
            needsPlacement = true;
            // 系统会把桌面窗口挪到手机屏上盖住触摸板，把 App 拉回前面
            refocusSelf();
        }
        @Override public void onDisplayChanged(int id) { }
    };

    private final DesktopSession.Listener sessionListener = new DesktopSession.Listener() {
        @Override public void onSessionChanged(boolean c, boolean restarted) {
            if (c) {
                ui.removeCallbacks(connectTimeout);
                showRunning();
                bumpIdle();
                // X 没有画面变化就不刷新，窗口刚接上时会一直黑屏：动一下光标触发重绘
                session.input().move(1, 0);
                session.input().move(-1, 0);
            }
            updateStatus();
            if (restarted) {
                setStatus(Style.WARN, "桌面被系统关闭后已重新启动，未保存的内容可能已丢失");
                ui.postDelayed(new Runnable() {
                    @Override public void run() { updateStatus(); }
                }, RESTART_NOTICE_MS);
                Display d = externalDisplay();
                if (d != null) placeX11(d.getDisplayId());
            }
        }
        @Override public void onSessionError(String message) {
            showSetup("无法调用 Termux", message, false, "重试", new View.OnClickListener() {
                @Override public void onClick(View v) { tryStart(); }
            });
        }
    };

    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { updateCapture(); }
        @Override public void onInputDeviceRemoved(int id) { updateCapture(); }
        @Override public void onInputDeviceChanged(int id) { }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override public void run() {
            if (!session.isConnected()) showConnectFailure();
        }
    };

    private final Runnable recapture = new Runnable() {
        @Override public void run() { updateCapture(); }
    };

    private final Runnable dim = new Runnable() {
        @Override public void run() { setDimmed(true); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setStatusBarColor(Style.BACKGROUND);
        getWindow().setNavigationBarColor(Style.BACKGROUND);
        displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        inputs = (InputManager) getSystemService(INPUT_SERVICE);
        ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        prefs = getPreferences(MODE_PRIVATE);
        session = DesktopSession.get(this);
        applyOrientation();
        lastOrientation = getResources().getConfiguration().orientation;
        setContentView(buildUi());
        screens.getViewTreeObserver().addOnGlobalLayoutListener(this::fitToKeyboard);
        session.setListener(sessionListener);
        displays.registerDisplayListener(displayListener, ui);
        inputs.registerInputDeviceListener(deviceListener, ui);
        tryStart();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // 从系统设置授权回来、或装好缺的 App 回来时接着走
        if (!session.isActive()) {
            tryStart();
            return;
        }
        Display d = externalDisplay();
        if (needsPlacement && d != null) {
            needsPlacement = false;
            placeX11(d.getDisplayId());
            updateStatus();
        }
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        displays.unregisterDisplayListener(displayListener);
        inputs.unregisterInputDeviceListener(deviceListener);
        ui.removeCallbacksAndMessages(null);
        session.setListener(null);
        super.onDestroy();
    }


    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            keyView.requestFocus();
            updateCapture();
        }
    }

    // --- 界面 ---

    private int dp(float v) {
        return Style.dp(this, v);
    }

    private View buildUi() {
        screens = new FrameLayout(this);
        screens.setBackgroundColor(Style.BACKGROUND);
        // 铺满摄像头挖孔那一侧：只避开系统栏和键盘，不避开挖孔
        screens.setOnApplyWindowInsetsListener((v, w) -> {
            Insets i = w.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        runningScreen = buildRunningScreen();
        setupScreen = buildSetupScreen();
        screens.addView(runningScreen, new FrameLayout.LayoutParams(-1, -1));
        screens.addView(setupScreen, new FrameLayout.LayoutParams(-1, -1));
        keyView = new KeyInputView(this, keySink);
        screens.addView(keyView, new FrameLayout.LayoutParams(1, 1));
        return screens;
    }

    private View buildSetupScreen() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER);
        s.setBackgroundColor(Style.BACKGROUND);
        s.setClickable(true);  // 挡住下面的触摸板，启动时误碰不会点到桌面
        s.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView name = new TextView(this);
        name.setText("XDesk");
        name.setTextColor(Style.ACCENT);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLetterSpacing(0.1f);
        name.setGravity(Gravity.CENTER);
        s.addView(name, new LinearLayout.LayoutParams(-2, -2));

        setupTitle = new TextView(this);
        setupTitle.setTextColor(Style.TEXT);
        setupTitle.setTextSize(24);
        setupTitle.setTypeface(Typeface.DEFAULT_BOLD);
        setupTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(16);
        s.addView(setupTitle, tp);

        setupMessage = new TextView(this);
        setupMessage.setTextColor(Style.TEXT_DIM);
        setupMessage.setTextSize(15);
        setupMessage.setGravity(Gravity.CENTER);
        setupMessage.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(12);
        s.addView(setupMessage, mp);

        setupProgress = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(40), dp(40));
        pp.topMargin = dp(28);
        s.addView(setupProgress, pp);

        setupAction = Style.button(this, "", Style.ACCENT, 15, 24);
        setupAction.setPadding(dp(28), dp(12), dp(28), dp(12));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = dp(28);
        s.addView(setupAction, ap);
        return s;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private View buildRunningScreen() {
        boolean landscape = isLandscape();
        latches.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(4), dp(8), dp(8));

        LinearLayout top = new LinearLayout(this);
        topBar = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), 0, 0, dp(4));
        statusDot = new View(this);
        top.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusText = new TextView(this);
        statusText.setTextColor(Style.TEXT);
        statusText.setTextSize(13);
        statusText.setMaxLines(2);
        statusText.setPadding(dp(8), 0, dp(8), 0);
        top.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        audioChip = Style.button(this, "", Style.SURFACE_RAISED, 12, 16);
        audioChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseOutput(); }
        });
        top.addView(audioChip, chipParams(dp(68)));
        TextView menu = Style.button(this, "设置", Style.SURFACE_RAISED, 12, 16);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        LinearLayout.LayoutParams mp = chipParams(dp(52));
        mp.leftMargin = dp(6);
        top.addView(menu, mp);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));
        updateAudioChip();

        diagText = new TextView(this);
        diagText.setTextColor(Style.TEXT_DIM);
        diagText.setTextSize(11);
        diagText.setPadding(dp(8), 0, dp(8), dp(4));
        root.addView(diagText, new LinearLayout.LayoutParams(-1, -2));
        applyDiagVisibility();

        LinearLayout keys = new LinearLayout(this);
        keys.addView(keyButton("Esc", KeyInputView.SCAN_ESC), keyParams());
        keys.addView(keyButton("Tab", KeyInputView.SCAN_TAB), keyParams());
        keys.addView(latchButton("Ctrl", KeyInputView.SCAN_CTRL), keyParams());
        keys.addView(latchButton("Alt", KeyInputView.SCAN_ALT), keyParams());
        keys.addView(latchButton("Super", KeyInputView.SCAN_SUPER), keyParams());
        keys.addView(latchButton("Shift", KeyInputView.SCAN_SHIFT), keyParams());
        keys.addView(keyButton("←", KeyInputView.SCAN_LEFT), keyParams());
        keys.addView(keyButton("↑", KeyInputView.SCAN_UP), keyParams());
        keys.addView(keyButton("↓", KeyInputView.SCAN_DOWN), keyParams());
        keys.addView(keyButton("→", KeyInputView.SCAN_RIGHT), keyParams());
        root.addView(keys, new LinearLayout.LayoutParams(-1, dp(40)));

        touchpad = new TouchpadView(this, output);
        touchpad.setSpeed(prefs.getFloat(PREF_SPEED, 1f));
        if (landscape) {
            // 横屏双手拿：左右键分在两侧，键盘在右手边
            LinearLayout middle = new LinearLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), -1);
            lp.rightMargin = dp(6);
            middle.addView(mouseButton("左键", 1), lp);
            middle.addView(touchpad, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.addView(mouseButton("右键", 3), new LinearLayout.LayoutParams(-1, 0, 2));
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, 0, 1);
            kp.topMargin = dp(6);
            side.addView(keyboardButton(), kp);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(88), -1);
            sp.leftMargin = dp(6);
            middle.addView(side, sp);
            LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(-1, 0, 1);
            mid.topMargin = dp(6);
            root.addView(middle, mid);
        } else {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1);
            tp.topMargin = dp(6);
            root.addView(touchpad, tp);
            LinearLayout bottom = new LinearLayout(this);
            bottom.addView(mouseButton("左键", 1), bottomParams(3));
            bottom.addView(mouseButton("右键", 3), bottomParams(3));
            bottom.addView(keyboardButton(), bottomParams(2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(64));
            bp.topMargin = dp(6);
            root.addView(bottom, bp);
        }
        return root;
    }

    private TextView keyboardButton() {
        TextView kb = Style.button(this, "键盘", Style.ACCENT, 14, 12);
        kb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Style.tick(v);
                toggleKeyboard();
            }
        });
        return kb;
    }

    /** 转屏不重建 Activity（窗口和输入都不能断），只换一套布局。 */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (c.orientation == lastOrientation) return;
        lastOrientation = c.orientation;
        releaseLatches();
        screens.removeView(runningScreen);
        runningScreen = buildRunningScreen();
        screens.addView(runningScreen, 0, new FrameLayout.LayoutParams(-1, -1));
        updateStatus();
        keyView.requestFocus();
        updateCapture();
    }

    /** 横屏弹出键盘时高度很紧，收起顶部状态栏。 */
    private void fitToKeyboard() {
        if (topBar == null) return;
        WindowInsets w = screens.getRootWindowInsets();
        boolean hide = isLandscape() && w != null && w.isVisible(WindowInsets.Type.ime());
        int topVisibility = hide ? View.GONE : View.VISIBLE;
        if (topBar.getVisibility() != topVisibility) {
            topBar.setVisibility(topVisibility);
            if (hide) diagText.setVisibility(View.GONE);
            else applyDiagVisibility();
        }
    }

    private LinearLayout.LayoutParams chipParams(int width) {
        return new LinearLayout.LayoutParams(width, dp(32));
    }

    private LinearLayout.LayoutParams keyParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.leftMargin = p.rightMargin = dp(2);
        return p;
    }

    private LinearLayout.LayoutParams bottomParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.leftMargin = p.rightMargin = dp(3);
        return p;
    }

    private TextView smallKey(String label) {
        return Style.button(this, label, Style.SURFACE_RAISED, 11, 8);
    }

    private TextView keyButton(String label, final int scan) {
        TextView b = smallKey(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Style.tick(v);
                keySink.tapKey(scan);
            }
        });
        return b;
    }

    /** 修饰键：点一下锁定（高亮），下一个按键或文字之后自动松开。 */
    private TextView latchButton(String label, int scan) {
        TextView b = smallKey(label);
        final Latch latch = new Latch(scan, b);
        latches.add(latch);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (client == null) return;
                Style.tick(v);
                setLatch(latch, !latch.on);
            }
        });
        return b;
    }

    private void setLatch(Latch l, boolean on) {
        l.on = on;
        if (client != null) client.key(l.scan, on);
        l.button.setBackground(Style.ripple(this, on ? Style.ACCENT : Style.SURFACE_RAISED, 8));
    }

    private void releaseLatches() {
        for (Latch l : latches) if (l.on) setLatch(l, false);
    }

    private TextView mouseButton(String label, final int button) {
        TextView b = Style.button(this, label, Style.SURFACE_RAISED, 14, 12);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (client == null) return false;
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    Style.tick(v);
                    client.button(button, true);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    client.button(button, false);
                }
                return true;
            }
        });
        return b;
    }

    private void toggleKeyboard() {
        WindowInsets w = getWindow().getDecorView().getRootWindowInsets();
        if (w != null && w.isVisible(WindowInsets.Type.ime())) {
            ime.hideSoftInputFromWindow(keyView.getWindowToken(), 0);
        } else {
            keyView.requestFocus();
            ime.showSoftInput(keyView, 0);
        }
    }

    // --- 状态 ---

    private void setStatus(int color, String s) {
        Log.i(TAG, s);
        statusText.setText(s);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        statusDot.setBackground(d);
    }

    private void updateStatus() {
        if (session.isConnected()) setStatus(Style.OK, "桌面已连接");
        else setStatus(Style.WARN, "连接中断，正在恢复…");
    }

    private void showSetup(String title, String message, boolean progress, String action, View.OnClickListener onAction) {
        setupScreen.setVisibility(View.VISIBLE);
        setupTitle.setText(title);
        setupMessage.setText(message);
        setupProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
        setupAction.setVisibility(action != null ? View.VISIBLE : View.GONE);
        if (action != null) {
            setupAction.setText(action);
            setupAction.setOnClickListener(onAction);
        }
    }

    private void showRunning() {
        setupScreen.setVisibility(View.GONE);
    }

    private void updateDiag() {
        if (diagText == null || keyView == null || diagText.getVisibility() != View.VISIBLE) return;
        diagText.setText("焦点" + yn(hasWindowFocus()) + " 键盘视图" + yn(keyView.isFocused()) + " 外接鼠标" + yn(hasExternalMouse())
            + " 抓取" + yn(captured) + " 按键" + keyEvents + " 鼠标" + mouseEvents);
    }

    private static String yn(boolean b) {
        return b ? "✓" : "✗";
    }

    private void applyDiagVisibility() {
        diagText.setVisibility(prefs.getBoolean(PREF_DIAG, false) ? View.VISIBLE : View.GONE);
        updateDiag();
    }

    // --- 省电：长时间不碰手机屏就调暗，外屏不受影响 ---

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        bumpIdle();
        return super.dispatchTouchEvent(e);
    }

    private void bumpIdle() {
        ui.removeCallbacks(dim);
        if (dimmed) setDimmed(false);
        if (session.isActive() && prefs.getBoolean(PREF_DIM, true)) ui.postDelayed(dim, DIM_AFTER_MS);
    }

    private void setDimmed(boolean d) {
        dimmed = d;
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.screenBrightness = d ? DIM_BRIGHTNESS : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(p);
    }

    // --- 设置 ---

    private int savedOutput() {
        return prefs.getInt(PREF_OUTPUT, AudioPlayer.OUT_PHONE);
    }

    private void updateAudioChip() {
        audioChip.setText("声音·" + OUTPUT_SHORT[savedOutput()]);
    }

    private void chooseOutput() {
        new AlertDialog.Builder(this)
            .setTitle("声音从哪里播放")
            .setSingleChoiceItems(OUTPUT_LABELS, savedOutput(), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    prefs.edit().putInt(PREF_OUTPUT, which).apply();
                    updateAudioChip();
                    if (audio != null && !audio.setOutput(which)) setStatus(Style.WARN, "现在没有这个输出设备，暂时走系统默认");
                    dialog.dismiss();
                }
            })
            .show();
    }

    /** 只影响 XDesk 的触控界面：手机系统锁竖屏时这里也能横过来。默认横屏。 */
    private void applyOrientation() {
        setRequestedOrientation(ORIENTATIONS[prefs.getInt(PREF_ORIENTATION, 0)]);
    }

    private void chooseOrientation() {
        new AlertDialog.Builder(this)
            .setTitle("触控界面方向")
            .setSingleChoiceItems(ORIENTATION_LABELS, prefs.getInt(PREF_ORIENTATION, 0), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    prefs.edit().putInt(PREF_ORIENTATION, which).apply();
                    applyOrientation();
                    dialog.dismiss();
                }
            })
            .show();
    }

    private int widthIndex() {
        int w = prefs.getInt(PREF_WIDTH, DEFAULT_WIDTH);
        for (int i = 0; i < WIDTHS.length; i++) if (WIDTHS[i] == w) return i;
        return 1;
    }

    private void chooseResolution() {
        new AlertDialog.Builder(this)
            .setTitle("桌面分辨率")
            .setSingleChoiceItems(WIDTH_LABELS, widthIndex(), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    prefs.edit().putInt(PREF_WIDTH, WIDTHS[which]).apply();
                    Display d = externalDisplay();
                    if (d != null) applySize(d);
                    dialog.dismiss();
                }
            })
            .show();
    }

    private TextView settingsRow(LinearLayout parent, String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Style.TEXT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(4), dp(14), dp(4), dp(14));
        t.setClickable(true);
        t.setOnClickListener(l);
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private void settingsSwitch(LinearLayout parent, String label, final String pref, boolean def, final Runnable changed) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Style.TEXT);
        s.setTextSize(15);
        s.setPadding(dp(4), dp(10), dp(4), dp(10));
        s.setChecked(prefs.getBoolean(pref, def));
        s.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.edit().putBoolean(pref, on).apply();
                changed.run();
            }
        });
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSettings() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(8), dp(20), dp(8));

        settingsRow(c, "声音输出", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseOutput(); }
        });
        settingsRow(c, "桌面分辨率", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseResolution(); }
        });
        settingsRow(c, "触控界面方向", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseOrientation(); }
        });

        TextView sl = new TextView(this);
        sl.setText("触摸板灵敏度");
        sl.setTextColor(Style.TEXT);
        sl.setTextSize(15);
        sl.setPadding(dp(4), dp(14), dp(4), dp(4));
        c.addView(sl, new LinearLayout.LayoutParams(-1, -2));
        SeekBar speed = new SeekBar(this);
        speed.setMax(150);  // 50%–200%
        speed.setProgress(Math.round(prefs.getFloat(PREF_SPEED, 1f) * 100) - 50);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = (p + 50) / 100f;
                touchpad.setSpeed(v);
                if (user) prefs.edit().putFloat(PREF_SPEED, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c.addView(speed, new LinearLayout.LayoutParams(-1, -2));

        settingsSwitch(c, "闲置 1 分钟调暗手机屏", PREF_DIM, true, new Runnable() {
            @Override public void run() { bumpIdle(); }
        });
        settingsSwitch(c, "显示诊断信息", PREF_DIAG, false, new Runnable() {
            @Override public void run() { applyDiagVisibility(); }
        });

        TextView end = settingsRow(c, "结束桌面", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmStop(); }
        });
        end.setTextColor(Style.DANGER);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(c);
        new AlertDialog.Builder(this)
            .setTitle("XDesk 设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .show();
    }

    // --- 物理键鼠 ---

    private boolean hasExternalMouse() {
        for (int id : inputs.getInputDeviceIds()) {
            InputDevice d = inputs.getInputDevice(id);
            if (d != null && d.isExternal() && d.supportsSource(InputDevice.SOURCE_MOUSE)) return true;
        }
        return false;
    }

    private void updateCapture() {
        if (keyView == null) return;
        if (hasWindowFocus()) {
            if (hasExternalMouse()) keyView.requestPointerCapture();
            else keyView.releasePointerCapture();
        }
        updateDiag();
    }

    /** 外接键盘直接转扫描码，手机自身的按键（返回、音量）不动。输入法没接走的才会到这里。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        InputDevice d = e.getDevice();
        keyEvents++;
        updateDiag();
        if (client != null && d != null && d.isExternal() && e.getScanCode() > 0) {
            if (e.getRepeatCount() == 0) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) client.key(e.getScanCode(), true);
                else if (e.getAction() == KeyEvent.ACTION_UP) client.key(e.getScanCode(), false);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // --- 会话 ---

    private Display externalDisplay() {
        for (Display d : displays.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void tryStart() {
        if (session.isActive()) {
            attachSession();
            return;
        }
        if (!installed(PKG_TERMUX)) {
            showSetup("缺少组件", "XDesk 需要 Termux。\n装好后回到这里会自动继续。", false, null, null);
            return;
        }
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            showSetup("需要一个权限", "XDesk 通过 Termux 启动桌面，需要允许它运行 Termux 命令。", false, "授权", new View.OnClickListener() {
                @Override public void onClick(View v) { requestPermissions(new String[]{PERM}, REQ_RUN_COMMAND); }
            });
            return;
        }
        Display d = externalDisplay();
        if (d == null) {
            showSetup("插上显示器", "用支持视频输出的 USB-C 线或扩展坞连接显示器，\n插上后会自动启动桌面。", false, null, null);
            return;
        }
        startDesktop(d);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (r != REQ_RUN_COMMAND) return;
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            tryStart();
            return;
        }
        showSetup("权限被拒绝", "在系统设置里找到 XDesk 的权限，允许“运行 Termux 命令”。", false, "打开设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
    }

    private void showConnectFailure() {
        session.abandon();
        client = null;
        audio = null;
        showSetup("桌面没有启动", "请确认 Termux 的 allow-external-apps 已打开。\n日志在 Termux 的 $PREFIX/tmp/xdesk.log。", false, "重试", new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(); }
        });
    }

    /** 界面重建时接回还在运行的会话。 */
    private void attachSession() {
        client = session.input();
        audio = session.audio();
        if (session.isConnected()) showRunning();
        else {
            showSetup("正在连接桌面", "桌面还在运行，正在重新连接。", true, null, null);
            ui.removeCallbacks(connectTimeout);
            ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        }
        updateStatus();
    }

    /** 分辨率的宽高比要和系统认定的外屏尺寸一致才能铺满（这台显示器系统认定 2560x1508）。 */
    private String desktopSize(Display d) {
        Point p = new Point();
        d.getRealSize(p);
        if (p.y > p.x) {
            Display.Mode m = d.getMode();
            p.set(m.getPhysicalWidth(), m.getPhysicalHeight());
        }
        int w = prefs.getInt(PREF_WIDTH, DEFAULT_WIDTH);
        if (w <= 0 || w > p.x) w = p.x;
        int h = Math.round((float) w * p.y / p.x) & ~1;
        return w + "x" + h;
    }

    /** 这个 App 的设置和官方 Termux:X11 是分开的，手机上的 xfce 不受影响。改了 X 会自动跟上。 */
    private void applySize(Display d) {
        String size = desktopSize(d);
        session.setSize(size);
        Prefs p = ((LorieApp) getApplication()).getPrefs(createDisplayContext(d));
        p.displayResolutionMode.put("custom");
        p.displayResolutionCustom.put(size);
        p.displayFilteringMode.put("bilinear");
        // 上游的虚拟按键栏会占掉外屏底部的高度，手机上已有快捷键栏
        p.showAdditionalKbd.put(false);
        p.additionalKbdVisible.put(false);
    }

    private void startDesktop(Display d) {
        // 没有通知权限服务照样运行，只是看不到通知，所以只问一次、不等结果
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        showSetup("正在启动桌面", "第一次启动可能要多等一会。", true, null, null);
        placeX11(d.getDisplayId());
        applySize(d);
        if (!session.start(desktopSize(d), savedOutput())) return;
        client = session.input();
        audio = session.audio();
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }



    /** 把上游的桌面窗口放到外屏。不要加 FLAG_ACTIVITY_CLEAR_TASK：重建的界面接不上运行中的 X，会显示 "X server stalled"。 */
    private void placeX11(int displayId) {
        try {
            Intent i = new Intent();
            i.setClass(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            startActivity(i, o.toBundle());
        } catch (RuntimeException e) {
            setStatus(Style.DANGER, "无法把桌面放到外屏: " + e.getMessage());
            return;
        }
        // 放置后焦点在 X11 上，拿回来，键盘和抓取鼠标才归 App
        refocusSelf();
    }

    private void refocusSelf() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                Intent self = new Intent(XDeskActivity.this, XDeskActivity.class);
                self.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(self);
            }
        }, REFOCUS_DELAY_MS);
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
            .setTitle("结束桌面？")
            .setMessage("XFCE 会退出，未保存的内容会丢失。")
            .setNegativeButton("取消", null)
            .setPositiveButton("结束", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    client = null;
                    audio = null;
                    session.stop();
                    MainActivity m = MainActivity.getInstance("");
                    if (m != null) m.finishAndRemoveTask();
                    finishAndRemoveTask();
                }
            })
            .show();
    }
}
