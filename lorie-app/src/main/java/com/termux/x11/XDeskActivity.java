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
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 打开后先选外接屏上运行什么（Termux 桌面或安卓应用），手机屏做触摸板和键盘。流程见 PLAN.md。 */
public class XDeskActivity extends Activity {
    private static final String TAG = "XDesk";
    private static final String PERM = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_RUN_COMMAND = 1, REQ_NOTIFICATIONS = 2, REQ_LIST_SESSIONS = 3;
    private static final String PKG_TERMUX = "com.termux";
    private static final String SCRIPT = "/data/data/com.termux/files/usr/bin/xdesk";
    private static final String SHIZUKU_HOME = "https://shizuku.rikka.app/";

    private static final String PREF_OUTPUT = "audio_output", PREF_WIDTH = "desktop_width", PREF_SPEED = "pad_speed",
        PREF_DIM = "dim_idle", PREF_DIAG = "show_diag", PREF_ORIENTATION = "orientation", PREF_RECENTS = "recents",
        PREF_SESSIONS = "sessions", PREF_MOUSE_SPEED = "mouse_speed";
    private static final int MAX_RECENTS = 6;
    private static final long TERMUX_REPLY_TIMEOUT_MS = 8000;
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
    private View runningScreen, setupScreen, topBar, sideRightButton;
    private ChooserView chooser;
    private AppSession apps;
    private int lastOrientation;
    private TextView setupTitle, setupMessage, setupAction, setupBack;
    private ProgressBar setupProgress;
    private TextView statusText, diagText, audioChip, forwardChip;
    private View statusDot;
    private TouchpadView touchpad;
    private KeyInputView keyView;

    private InputSink client;
    private AudioPlayer audio;
    private DesktopSession session;
    private boolean captured, dimmed, resumed;
    /** 桌面窗口离开了外屏，还没放回去。App 在后台时系统可能拦下放置，回到前台再补。 */
    private boolean needsPlacement;
    /** 选了这个安卓应用，还差权限或 Shizuku。回到前台或 Shizuku 有变化时接着走。 */
    private ChooserView.App pendingApp;
    /** launchDesktop 等 RUN_COMMAND 权限时记着选的是什么。 */
    private String pendingSession;
    private boolean pendingReplace;
    /** 最近一次知道的 Termux 状态：运行中的环境。 */
    private String runningSession;
    private final Runnable termuxNoReply = new Runnable() {
        @Override public void run() {
            setChooserTermux(savedSessions(), null, null, false, "Termux 没有回应。在 Termux 里运行 xdesk enable-app-start 后再回来");
        }
    };
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
            if (apps.isActive()) {
                Display added = displays.getDisplay(id);
                if (added != null) apps.displayAdded(added);
                return;
            }
            if (!session.isActive()) {
                updateChooserDisplay();
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
            if (apps.isActive()) {
                apps.displayRemoved(id);
                refocusSelf();
                return;
            }
            if (!session.isActive()) {
                updateChooserDisplay();
                return;
            }
            setStatus(Style.WARN, "外接屏已断开，桌面还在运行，插回后自动恢复");
            needsPlacement = true;
            // 系统会把桌面窗口挪到手机屏上盖住触摸板，把 App 拉回前面
            refocusSelf();
        }
        @Override public void onDisplayChanged(int id) {
            if (apps.isActive()) apps.displayChanged(id);
        }
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
            if (!c && !session.isRecovering()) showWaiting();
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
            showSetup("无法调用 Termux", message, false, "返回选择", new View.OnClickListener() {
                @Override public void onClick(View v) { backToChooser(); }
            });
        }
    };

    private final AppSession.Listener appListener = new AppSession.Listener() {
        @Override public void onShizukuChanged() {
            if (pendingApp != null) startApp(pendingApp);
            else apps.reconnect();
            if (apps.isActive()) updateStatus();
        }
        @Override public void onAppStarted() {
            attachApp();
            refocusSelf();
        }
        @Override public void onAppFailed(String message) {
            client = null;
            showSetup("无法在外接屏上运行", message, false, "返回选择", new View.OnClickListener() {
                @Override public void onClick(View v) { backToChooser(); }
            });
        }
        @Override public void onAppStateChanged() {
            updateStatus();
        }
        @Override public void onAppMessage(String message) {
            Toast.makeText(XDeskActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    private final ChooserView.Host chooserHost = new ChooserView.Host() {
        @Override public void onSessionChosen(final String name) {
            if (externalDisplay() == null) {
                needDisplayNotice();
                return;
            }
            rememberRecent(ChooserView.recentKey(name));
            if (name.equals(runningSession)) {
                reattach();
            } else if (runningSession != null) {
                new AlertDialog.Builder(XDeskActivity.this)
                    .setTitle("切换桌面？")
                    .setMessage("会结束正在运行的 " + runningSession + "，未保存的内容会丢失。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("切换", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) { launchDesktop(name, true); }
                    })
                    .show();
            } else {
                launchDesktop(name, false);
            }
        }
        @Override public void onAttachChosen() {
            Display d = externalDisplay();
            if (d == null) {
                needDisplayNotice();
                return;
            }
            attachDisplay(d);
            showWaiting();
        }
        @Override public void onAppChosen(ChooserView.App app) {
            startApp(app);
        }
        @Override public void onTermuxHint() {
            if (installed(PKG_TERMUX) && checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{PERM}, REQ_LIST_SESSIONS);
            } else {
                refreshTermux();
            }
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
        apps = AppSession.get(this);
        applyOrientation();
        lastOrientation = getResources().getConfiguration().orientation;
        setContentView(buildUi());
        screens.getViewTreeObserver().addOnGlobalLayoutListener(this::fitToKeyboard);
        session.setListener(sessionListener);
        apps.setListener(appListener);
        displays.registerDisplayListener(displayListener, ui);
        inputs.registerInputDeviceListener(deviceListener, ui);
        tryStart();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // 从系统设置或 Shizuku 授权回来、或装好缺的 App 回来时接着走
        if (pendingApp != null) {
            startApp(pendingApp);
            return;
        }
        if (apps.isActive()) return;
        if (!session.isActive()) {
            if (chooser.getVisibility() == View.VISIBLE) refreshChooser();
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
        apps.setListener(null);
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
        chooser = new ChooserView(this, chooserHost);
        screens.addView(chooser, new FrameLayout.LayoutParams(-1, -1));
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

        setupBack = Style.button(this, "返回选择", Style.SURFACE_RAISED, 14, 24);
        setupBack.setPadding(dp(28), dp(10), dp(28), dp(10));
        setupBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { backToChooser(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(12);
        s.addView(setupBack, bp);
        return s;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private View buildRunningScreen() {
        boolean landscape = isLandscape();
        sideRightButton = null;
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
        forwardChip = Style.button(this, "", Style.ACCENT, 12, 16);
        forwardChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Style.tick(v);
                apps.setForwarding(!apps.isForwarding());
            }
        });
        top.addView(forwardChip, chipParams(dp(84)));
        TextView back = Style.button(this, "返回", Style.SURFACE_RAISED, 12, 16);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { backToChooser(); }
        });
        LinearLayout.LayoutParams backParams = chipParams(dp(52));
        backParams.leftMargin = dp(6);
        top.addView(back, backParams);
        TextView menu = Style.button(this, "设置", Style.SURFACE_RAISED, 12, 16);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        LinearLayout.LayoutParams mp = chipParams(dp(52));
        mp.leftMargin = dp(6);
        top.addView(menu, mp);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));
        updateAudioChip();
        applyModeToTopBar();

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
            sideRightButton = mouseButton("右键", 3);
            side.addView(sideRightButton, new LinearLayout.LayoutParams(-1, 0, 2));
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
            // 键盘弹出后高度不够放两个按钮，右键先让位，“键盘”按钮才点得到
            if (sideRightButton != null) sideRightButton.setVisibility(topVisibility);
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

    /** 安卓应用没有声音输出可选，桌面没有键鼠可接管。 */
    private void applyModeToTopBar() {
        boolean app = apps.isActive();
        audioChip.setVisibility(app ? View.GONE : View.VISIBLE);
        forwardChip.setVisibility(app ? View.VISIBLE : View.GONE);
    }

    private void updateAppStatus() {
        forwardChip.setText(apps.isForwarding() ? "交还键鼠" : "接管键鼠");
        forwardChip.setBackground(Style.ripple(this, apps.isForwarding() ? Style.ACCENT : Style.SURFACE_RAISED, 16));
        if (!apps.hasInjector()) {
            setStatus(Style.DANGER, "注入服务断开了，确认 Shizuku 还在运行");
        } else if (!apps.hasDisplay()) {
            setStatus(Style.WARN, "外接屏已断开，插回后 " + apps.label() + " 自动回去");
        } else if (apps.isForwarding() && apps.deviceCount() == 0) {
            setStatus(Style.WARN, apps.label() + "：没有找到外接键鼠，插上后自动接管");
        } else if (apps.isForwarding()) {
            setStatus(Style.OK, apps.label() + "：键鼠在外接屏，Ctrl+Alt+M 交还手机");
        } else {
            setStatus(Style.OK, apps.label() + "：键鼠在手机，Ctrl+Alt+M 接管");
        }
    }

    private void updateStatus() {
        applyModeToTopBar();
        if (apps.isActive()) {
            updateAppStatus();
            return;
        }
        if (session.isConnected()) setStatus(Style.OK, "桌面已连接");
        else setStatus(Style.WARN, session.isRecovering() ? "连接中断，正在恢复…" : "桌面未连接");
    }

    private void showSetup(String title, String message, boolean progress, String action, View.OnClickListener onAction) {
        chooser.setVisibility(View.GONE);
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
        chooser.setVisibility(View.GONE);
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
        if (apps.isActive() && e.getActionMasked() == MotionEvent.ACTION_DOWN) apps.phoneTouched();
        return super.dispatchTouchEvent(e);
    }

    private void bumpIdle() {
        ui.removeCallbacks(dim);
        if (dimmed) setDimmed(false);
        if ((session.isActive() || apps.isActive()) && prefs.getBoolean(PREF_DIM, true)) ui.postDelayed(dim, DIM_AFTER_MS);
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

        boolean appMode = apps.isActive();
        if (!appMode) {
            settingsRow(c, "声音输出", new View.OnClickListener() {
                @Override public void onClick(View v) { chooseOutput(); }
            });
            settingsRow(c, "桌面分辨率", new View.OnClickListener() {
                @Override public void onClick(View v) { chooseResolution(); }
            });
        }
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

        if (appMode) {
            TextView ml = new TextView(this);
            ml.setText("实体鼠标速度");
            ml.setTextColor(Style.TEXT);
            ml.setTextSize(15);
            ml.setPadding(dp(4), dp(14), dp(4), dp(4));
            c.addView(ml, new LinearLayout.LayoutParams(-1, -2));
            SeekBar mouseSpeed = new SeekBar(this);
            mouseSpeed.setMax(150);  // 50%–200%，100% 按显示器宽度自动算
            mouseSpeed.setProgress(Math.round(prefs.getFloat(PREF_MOUSE_SPEED, 1f) * 100) - 50);
            mouseSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (!user) return;
                    float v = (p + 50) / 100f;
                    prefs.edit().putFloat(PREF_MOUSE_SPEED, v).apply();
                    apps.setMouseSpeed(v);
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            c.addView(mouseSpeed, new LinearLayout.LayoutParams(-1, -2));
        }

        settingsSwitch(c, "闲置 1 分钟调暗手机屏", PREF_DIM, true, new Runnable() {
            @Override public void run() { bumpIdle(); }
        });
        settingsSwitch(c, "显示诊断信息", PREF_DIAG, false, new Runnable() {
            @Override public void run() { applyDiagVisibility(); }
        });

        final AlertDialog[] dialog = new AlertDialog[1];
        settingsRow(c, appMode ? "返回选择（停止转发，应用留在外接屏）" : "返回选择（桌面继续运行）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog[0].dismiss();
                backToChooser();
            }
        });
        settingsRow(c, appMode ? "退出 XDesk（停止转发，应用留在外接屏）" : "退出 XDesk（桌面继续运行）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog[0].dismiss();
                backToChooser();
                finishAndRemoveTask();
            }
        });
        if (!appMode) {
            TextView end = settingsRow(c, "结束桌面", new View.OnClickListener() {
                @Override public void onClick(View v) { confirmStop(); }
            });
            end.setTextColor(Style.DANGER);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(c);
        dialog[0] = new AlertDialog.Builder(this)
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
            // 只有 Termux 桌面才抓取。选择界面要用鼠标点，安卓应用的键鼠由注入服务接管，没接管时就该留给手机
            if (session.isActive() && hasExternalMouse()) keyView.requestPointerCapture();
            else keyView.releasePointerCapture();
        }
        updateDiag();
    }

    /** 外接键盘直接转扫描码，手机自身的按键（返回、音量）不动。输入法没接走的才会到这里。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        InputDevice d = e.getDevice();
        keyEvents++;
        updateDiag();
        if (client != null && !apps.isActive() && d != null && d.isExternal() && e.getScanCode() > 0) {
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

    /** 接回这个进程里还在运行的东西。什么都没有就显示选择界面，不会自己启动桌面。 */
    private void tryStart() {
        if (session.isActive()) {
            attachSession();
        } else if (apps.isActive()) {
            if (apps.isStarted()) attachApp();
            else showSetup("正在启动", "正在连接注入服务。", true, null, null);
        } else {
            showChooser();
        }
    }

    // --- 选择界面 ---

    private void showChooser() {
        setupScreen.setVisibility(View.GONE);
        chooser.setVisibility(View.VISIBLE);
        keyView.releasePointerCapture();
        refreshChooser();
    }

    private void refreshChooser() {
        updateChooserDisplay();
        chooser.setRecents(savedRecents());
        refreshTermux();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ChooserView.App> list = ChooserView.loadApps(XDeskActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() { chooser.setApps(list); }
                });
            }
        }, "xdesk-apps").start();
    }

    private void updateChooserDisplay() {
        chooser.setDisplayConnected(externalDisplay() != null);
    }

    private void needDisplayNotice() {
        Toast.makeText(this, "先用支持视频输出的 USB-C 线或扩展坞插上显示器", Toast.LENGTH_LONG).show();
    }

    private List<String> savedLines(String pref) {
        String saved = prefs.getString(pref, "");
        if (saved.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(Arrays.asList(saved.split("\n")));
    }

    private List<String> savedRecents() {
        return savedLines(PREF_RECENTS);
    }

    private List<String> savedSessions() {
        return savedLines(PREF_SESSIONS);
    }

    private void rememberRecent(String key) {
        List<String> r = savedRecents();
        r.remove(key);
        r.add(0, key);
        while (r.size() > MAX_RECENTS) r.remove(r.size() - 1);
        prefs.edit().putString(PREF_RECENTS, String.join("\n", r)).apply();
        chooser.setRecents(r);
    }

    private void setChooserTermux(List<String> sessions, String defaultName, String running, boolean x, String hint) {
        runningSession = running;
        chooser.setTermux(sessions, defaultName, running, x, hint);
    }

    /** 列出环境和正在运行的东西。列不出时用上次的结果，并说明原因。 */
    private void refreshTermux() {
        ui.removeCallbacks(termuxNoReply);
        if (!installed(PKG_TERMUX)) {
            setChooserTermux(new ArrayList<String>(), null, null, false, "没有安装 Termux，装好后点这里");
            return;
        }
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            setChooserTermux(savedSessions(), null, null, false, "点这里允许 XDesk 读取 Termux 环境");
            return;
        }
        setChooserTermux(savedSessions(), null, null, false, null);
        boolean sent = TermuxCommand.run(this, SCRIPT + " list; echo ---; " + SCRIPT + " status", new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) { parseTermuxState(stdout); }
        });
        if (sent) ui.postDelayed(termuxNoReply, TERMUX_REPLY_TIMEOUT_MS);
        else termuxNoReply.run();
    }

    /** 解析 xdesk list 和 xdesk status 的输出。 */
    private void parseTermuxState(String stdout) {
        ui.removeCallbacks(termuxNoReply);
        List<String> sessions = new ArrayList<String>();
        String defaultName = null, running = null;
        boolean status = false, x = false;
        for (String line : stdout.split("\n")) {
            if (line.equals("---")) {
                status = true;
            } else if (!status) {
                if (line.startsWith("* ") || line.startsWith("  ")) {
                    String name = line.substring(2).trim();
                    if (name.isEmpty()) continue;
                    sessions.add(name);
                    if (line.startsWith("* ")) defaultName = name;
                }
            } else if (line.startsWith("X server: running")) {
                x = true;
            } else if (line.startsWith("Session: ") && !line.equals("Session: none")) {
                running = line.substring("Session: ".length()).trim();
            }
        }
        prefs.edit().putString(PREF_SESSIONS, String.join("\n", sessions)).apply();
        setChooserTermux(sessions, defaultName, running, x, sessions.isEmpty() ? "没有找到环境。在 Termux 里运行 xdesk setup 后点这里" : null);
    }

    /** 回到选择界面。桌面继续运行，安卓应用留在外接屏上，只停止转发和光标。 */
    private void backToChooser() {
        pendingApp = null;
        pendingSession = null;
        ui.removeCallbacks(connectTimeout);
        releaseLatches();
        client = null;
        audio = null;
        if (apps.isActive()) apps.stop();
        if (session.isActive()) {
            session.abandon();
            MainActivity m = MainActivity.getInstance("");
            if (m != null) m.finishAndRemoveTask();
        }
        showChooser();
    }

    // --- 安卓应用 ---

    /** 权限和 Shizuku 都就绪才启动。缺什么说什么，弄好了回来会接着走（onResume、onShizukuChanged）。 */
    private void startApp(final ChooserView.App app) {
        Display d = externalDisplay();
        if (d == null) {
            pendingApp = null;
            needDisplayNotice();
            return;
        }
        pendingApp = app;
        int shizuku = apps.shizukuState();
        if (shizuku == AppSession.SHIZUKU_NOT_INSTALLED) {
            showSetup("需要 Shizuku", "安卓应用的键鼠转发要靠 Shizuku（不用 root）。\nTermux 桌面不需要它。\n\n装好并启动 Shizuku 后回到这里。", false, "打开 Shizuku 网站", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HOME))); }
            });
        } else if (shizuku == AppSession.SHIZUKU_NOT_RUNNING) {
            showSetup("Shizuku 没有运行", "打开 Shizuku，按里面的说明用无线调试启动它，\n然后回到这里，会自动继续。", false, "打开 Shizuku", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent open = getPackageManager().getLaunchIntentForPackage(AppSession.SHIZUKU_PACKAGE);
                    if (open != null) startActivity(open);
                }
            });
        } else if (shizuku == AppSession.SHIZUKU_TOO_OLD) {
            showSetup("Shizuku 版本太旧", "请升级 Shizuku 后回到这里。", false, "打开 Shizuku 网站", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HOME))); }
            });
        } else if (shizuku == AppSession.SHIZUKU_NO_PERMISSION) {
            if (apps.permissionDeniedForGood()) {
                showSetup("Shizuku 拒绝了 XDesk", "之前选了不再询问。在 Shizuku 的应用管理里允许 XDesk，\n然后回到这里。", false, "打开 Shizuku", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent open = getPackageManager().getLaunchIntentForPackage(AppSession.SHIZUKU_PACKAGE);
                        if (open != null) startActivity(open);
                    }
                });
            } else {
                showSetup("授权 Shizuku", "XDesk 要通过 Shizuku 把键鼠转发给外接屏上的应用。", false, "授权", new View.OnClickListener() {
                    @Override public void onClick(View v) { apps.requestPermission(); }
                });
            }
        } else if (!Settings.canDrawOverlays(this)) {
            showSetup("需要悬浮窗权限", "安卓自己的光标只留在手机屏上，\nXDesk 要在外接屏上画一个光标。\n\n允许后回到这里，会自动继续。", false, "打开设置", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                }
            });
        } else {
            pendingApp = null;
            rememberRecent(ChooserView.recentKey(app));
            showSetup("正在启动 " + app.label, "正在连接注入服务。", true, null, null);
            apps.start(app, d, prefs.getFloat(PREF_MOUSE_SPEED, 1f));
        }
    }

    private void attachApp() {
        client = apps.input();
        audio = null;
        showRunning();
        updateCapture();
        updateStatus();
        bumpIdle();
    }

    // --- Termux 桌面 ---

    /** XDesk 只当显示器：放好窗口就等桌面连上来，桌面怎么起由用户决定。 */
    private void attachDisplay(Display d) {
        placeX11(d.getDisplayId());
        applySize(d);
        session.attach(savedOutput());
        client = session.input();
        audio = session.audio();
        updateCapture();
    }

    /** 接回已经在运行的桌面，不启动也不重启任何东西。 */
    private void reattach() {
        Display d = externalDisplay();
        if (d == null) return;
        attachDisplay(d);
        pendingSession = runningSession;
        pendingReplace = false;
        showSetup("桌面正在运行", "正在接回 " + runningSession + "。", true, null, null);
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void showWaiting() {
        showSetup("等待桌面", "在 Termux 里运行：\n\nxdesk start\n\n也可以运行任意程序：xdesk run 命令", false, "一键启动默认环境", new View.OnClickListener() {
            @Override public void onClick(View v) { launchDesktop(null, false); }
        });
    }

    /**
     * 替用户运行 xdesk start，只由用户的选择触发。只有这里需要 Termux 的权限。
     *
     * @param name 环境名，null 是默认环境
     * @param replace 结束运行中的另一个环境
     */
    private void launchDesktop(String name, boolean replace) {
        if (!installed(PKG_TERMUX)) {
            showSetup("缺少组件", "XDesk 需要 Termux。\n装好后回到这里再选一次。", false, null, null);
            return;
        }
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            pendingSession = name;
            pendingReplace = replace;
            requestPermissions(new String[]{PERM}, REQ_RUN_COMMAND);
            return;
        }
        pendingSession = name;
        pendingReplace = replace;
        if (!session.isActive()) {
            Display d = externalDisplay();
            if (d == null) {
                needDisplayNotice();
                showChooser();
                return;
            }
            attachDisplay(d);
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        showSetup("正在启动桌面", "第一次启动可能要多等一会。", true, null, null);
        if (!session.launch(name, replace)) return;
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (r == REQ_LIST_SESSIONS) {
            refreshTermux();
            return;
        }
        if (r != REQ_RUN_COMMAND) return;
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            launchDesktop(pendingSession, pendingReplace);
            return;
        }
        showSetup("没有运行 Termux 命令的权限", "可以在 Termux 里自己运行 xdesk start，\n或者在系统设置里允许 XDesk“运行 Termux 命令”。", false, "打开设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
    }

    private void showConnectFailure() {
        showSetup("桌面没有启动", "在 Termux 里运行 xdesk enable-app-start 和 xdesk setup，\n也可以直接运行 xdesk start 看报错。\n日志在 $PREFIX/tmp/xdesk.log。", false, "重试", new View.OnClickListener() {
            @Override public void onClick(View v) { launchDesktop(pendingSession, pendingReplace); }
        });
    }

    /** 界面重建时接回还在运行的会话。 */
    private void attachSession() {
        client = session.input();
        audio = session.audio();
        if (session.isConnected()) {
            showRunning();
            updateStatus();
        } else if (session.isRecovering()) {
            showSetup("正在连接桌面", "正在重新连接。", true, null, null);
            ui.removeCallbacks(connectTimeout);
            ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        } else {
            showWaiting();
        }
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
        Prefs p = ((LorieApp) getApplication()).getPrefs(createDisplayContext(d));
        p.displayResolutionMode.put("custom");
        p.displayResolutionCustom.put(size);
        p.displayFilteringMode.put("bilinear");
        // 上游的虚拟按键栏会占掉外屏底部的高度，手机上已有快捷键栏
        p.showAdditionalKbd.put(false);
        p.additionalKbdVisible.put(false);
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
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                .setTitle("结束桌面")
                .setMessage("XDesk 没有运行 Termux 命令的权限。请在 Termux 里运行：\n\nxdesk stop")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("结束桌面？")
            .setMessage("桌面会退出，未保存的内容会丢失。")
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
