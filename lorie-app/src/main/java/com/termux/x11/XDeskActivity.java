package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.Context;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.x11.app.R;
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
    private static final String TERMUX_STATE_COMMAND = SCRIPT + " list; echo ---; " + SCRIPT + " status";
    private static final String SHIZUKU_HOME = "https://shizuku.rikka.app/";
    private static final String CAR_GONE = "桌面还在运行，在车机上重新打开 XDesk 后自动恢复";

    private static final String PREF_SCROLL_SPEED = "pad_scroll_speed", PREF_COVER = "cover_external";
    private static final String PREF_OUTPUT = "audio_output", PREF_WIDTH = "desktop_width", PREF_SPEED = "pad_speed",
        PREF_DIM = "dim_idle", PREF_DIAG = "show_diag", PREF_ORIENTATION = "orientation", PREF_RECENTS = "recents",
        PREF_SESSIONS = "sessions", PREF_MOUSE_SPEED = "mouse_speed", PREF_DOCK = "dock", PREF_ALWAYS_CURSOR = "always_cursor",
        PREF_FULL_PAD = "pad_fullscreen";
    private static final int MAX_RECENTS = 6;
    private static final long TERMUX_REPLY_TIMEOUT_MS = 8000;
    /** 切换时等 Termux 说哪个环境在运行，等不到就按上次知道的。 */
    private static final long TERMUX_SWITCH_WAIT_MS = 3000;
    /** xdesk stop 要等桌面退出，过一会再看状态。 */
    private static final long STOP_REFRESH_MS = 4000;
    private static final String[] WIDTH_LABELS = {"清晰（显示器原生）", "均衡（1920 宽）", "省电和游戏（1280 宽）"};
    private static final int[] WIDTHS = {0, 1920, 1280};  // 0 = 原生
    private static final int DEFAULT_WIDTH = 1920;
    private static final String[] ORIENTATION_LABELS = {"跟随系统", "竖屏", "横屏"};
    /** 存的值，顺序同上。没选过就跟随系统；以前明确选过的不变。 */
    private static final int[] ORIENTATION_VALUES = {2, 1, 0};
    private static final int ORIENTATION_DEFAULT = 2;
    private static final int SIDE_WIDTH = 72, SIDE_WIDTH_TOUCH = 56;
    private static final int[] ORIENTATIONS = {ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_FULL_USER};

    private static final long CONNECT_TIMEOUT_MS = 40000;
    /** 注入服务还在结束时选了应用：等它结束再启动，否则新的会被一起结束。 */
    private static final long STOPPING_RETRY_MS = 200;
    private static final int DENSITY_MIN = 120, DENSITY_MAX = 220;
    private static final long RECAPTURE_DELAY_MS = 500;
    private static final long REFOCUS_DELAY_MS = 800;
    private static final int SCAN_A = 30, SCAN_X = 45, SCAN_C = 46, SCAN_V = 47, SCAN_Z = 44;
    private static final long DIM_AFTER_MS = 60000;
    private static final float DIM_BRIGHTNESS = 0.01f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private InputManager inputs;
    private InputMethodManager ime;
    private SharedPreferences prefs;

    private FrameLayout screens;
    private ExternalCover cover;
    private boolean forwardingBeforeCover;
    private View keyboardBtn, comboRow;
    private View runningScreen, setupScreen, topBar, keysRow, leftButton, rightButton, leftColumn, rightColumn;
    private Style.IconButton keysHandle;
    private DirectTouchView directTouch;
    private boolean keysExpanded, keyboardOpen, phonePassive;
    /** 触控区铺满整个手机屏，系统栏隐藏。 */
    private boolean padFullscreen;
    /** 这个显示器已经放过仪表盘了，放不上去也别一直试。 */
    private int dashboardTried = Display.INVALID_DISPLAY;
    /** 认得的外接屏。拔掉时系统已经查不到它是什么屏了，只能靠记下的 id 认出来。 */
    private final java.util.Set<Integer> realDisplays = new java.util.HashSet<Integer>();
    /** 上次知道的车机显示器，没有是 INVALID_DISPLAY。 */
    private int carSeen = Display.INVALID_DISPLAY;
    /** 桌面放在了车机上。会话进行中不因为另一种屏接上就挪走。会话在进程里，界面重建后也要知道，所以是 static。 */
    private static boolean desktopOnCar;
    private ChooserView chooser;
    private AppSession apps;
    private AppCatalog catalog;
    private AudioRoute audioRoute;
    private DockBar runningDock;
    private Dialog outputDialog;
    /** 设置页开着时的声音输出那一行。 */
    private TextView settingsOutputValue;
    /** 这次接上的桌面环境名，只当显示器时不知道。 */
    private String desktopName;
    private int lastOrientation;
    private TextView setupTitle, setupMessage, setupAction, setupSecond, setupBack;
    private ProgressBar setupProgress;
    private TextView statusText, diagText;
    private Style.IconButton audioBtn, touchBtn, forwardBtn, fullscreenBtn, launcherBtn, settingsBtn, backBtn;
    private View statusDot;
    private TouchpadView touchpad;
    private KeyInputView keyView;

    private InputSink client;
    private DesktopSession session;
    private boolean captured, dimmed, resumed;
    /** 桌面窗口离开了外屏，还没放回去。App 在后台时系统可能拦下放置，回到前台再补。 */
    private boolean needsPlacement;
    /** 选了这个安卓应用，还差权限或 Shizuku。回到前台或 Shizuku 有变化时接着走。 */
    private AppCatalog.App pendingApp;
    /** 正开着的激活页。 */
    private ActivationPage activation;
    /** 从启动器的激活提示进来、还没完成：启动好了要回到启动器。 */
    private boolean activationFlow;
    /** 用户刚点了要启动：showActivation 这时才重试注入服务。 */
    private boolean retryRequested;
    /** 用户在激活页选了 Shizuku：这次不再回到激活页。 */
    private boolean shizukuScreens;
    /** 点了配对，先要通知权限。 */
    private boolean pairingAfterPermission;
    private String shownShellProblem;
    /** launchDesktop 等 RUN_COMMAND 权限时记着选的是什么。 */
    private String pendingSession;
    private boolean pendingReplace;
    /** 最近一次知道的 Termux 状态：运行中的环境。 */
    private String runningSession;
    private final Runnable termuxNoReply = new Runnable() {
        @Override public void run() {
            setChooserTermux(savedSessions(), null, runningSession, false, "Termux 没有回应。在 Termux 里运行 xdesk enable-app-start 后再回来");
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
            // 只管外接屏。车机的显示器要等 CarActivity 起来才认得出（carChanged），Android Auto 别的显示器都不管
            Display added = displays.getDisplay(id);
            if (!TargetDisplay.isReal(added)) return;
            realDisplays.add(id);
            // 桌面在车机上：外接屏后来接上也不挪
            if (session.isActive() && desktopOnCar && TargetDisplay.car(displays) != null) return;
            // 放在最底下，所以要先放它，再放应用或桌面窗口
            dashboardTried = Display.INVALID_DISPLAY;
            startDashboard(added);
            if (apps.isActive()) {
                apps.displayAdded(added);
                return;
            }
            if (!session.isActive()) {
                updateChooserDisplay();
                return;
            }
            // 可能换了显示器：按新尺寸算分辨率，再把桌面放回去
            desktopOnCar = false;
            applySize(added);
            placeX11(id);
            needsPlacement = !resumed;
            updateStatus();
        }
        @Override public void onDisplayRemoved(int id) {
            if (id == carSeen) {
                carChanged();
                return;
            }
            if (!realDisplays.remove(id)) return;
            removeCover();
            // 不收的话系统会把它挪到手机屏上
            DashboardActivity.closeIfAlive();
            dashboardTried = Display.INVALID_DISPLAY;
            if (!session.isActive() && !apps.isActive()) DesktopService.stopIfIdle(XDeskActivity.this);
            if (apps.isActive()) {
                apps.displayRemoved(id);
                refocusSelf();
                return;
            }
            // 桌面在车机上时拔掉外接屏，桌面不受影响
            if (!session.isActive() || desktopOnCar) {
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
        @Override public void onSessionChanged(boolean c) {
            if (c) {
                ui.removeCallbacks(connectTimeout);
                showRunning();
                bumpIdle();
                // X 没有画面变化就不刷新，窗口刚接上时会一直黑屏：动一下光标触发重绘
                session.input().move(1, 0);
                session.input().move(-1, 0);
            }
            // 车机断开时系统可能直接关掉桌面窗口：X 还在，等车机回来
            if (!c && desktopOnCar && TargetDisplay.car(displays) == null) {
                showSetup("车机已断开", CAR_GONE, false, "回到启动器", new View.OnClickListener() {
                    @Override public void onClick(View v) { backToChooser(); }
                });
            } else if (!c) {
                showWaiting();
            }
            updateStatus();
        }
        @Override public void onDesktopStopped(boolean byUser) {
            // 不自动重启：启动和结束桌面都由用户决定
            runningSession = null;
            desktopName = null;
            desktopGone();
            String stopped = byUser ? "桌面已结束。" : "桌面意外停止了，不会自动重启。";
            Toast.makeText(XDeskActivity.this, stopped, Toast.LENGTH_LONG).show();
            chooser.setNotice(byUser ? "桌面已结束。点下面的环境可以重新启动。"
                : "桌面意外停止了（X 服务器退出了），XDesk 不会自动重启。未保存的内容可能已丢失。点下面的环境可以重新启动。");
        }
        @Override public void onSessionError(String message) {
            showSetup("无法调用 Termux", message, false, "回到启动器", new View.OnClickListener() {
                @Override public void onClick(View v) { backToChooser(); }
            });
        }
    };

    private final AppSession.Listener appListener = new AppSession.Listener() {
        @Override public void onInjectorSourceChanged() {
            ShellActivation shell = apps.shell();
            // 配对成功了：配对页不再有用，改成显示启动的进度或问题
            boolean pairedNow = activation != null && activation.isShowing() && shell.isPaired() && !shell.isPairing();
            if (pairedNow) closeActivation();
            else if (activation != null && activation.isShowing()) activation.refresh();
            updateActivationPrompt();
            if (pendingApp != null) {
                startApp(pendingApp);
            } else if (pairedNow || activationFlow) {
                // 从启动器的提示进来的：好了就回启动器，没好就留在进度或问题上
                if (apps.injectorReady()) {
                    activationFlow = false;
                    backToChooser();
                } else {
                    activationFlow = true;
                    showActivation(null);
                }
                apps.reconnect();
            } else {
                // 配对时外接屏断开了，要启动的应用已经取消
                if (activation != null && apps.injectorReady()) backToChooser();
                apps.reconnect();
                showShellProblem();
            }
            if (session.isActive()) apps.connectForAudio();
            if (apps.isActive()) updateStatus();
        }
        @Override public void onAppStarted() {
            attachApp();
            refocusSelf();
        }
        @Override public void onAppFailed(String message) {
            client = null;
            showSetup("无法在外接屏上运行", message, false, "回到启动器", new View.OnClickListener() {
                @Override public void onClick(View v) { backToChooser(); }
            });
        }
        @Override public void onAppStateChanged() {
            updateStatus();
            refreshDocks();
        }
        @Override public void onAppGone() {
            openChooserOverSession();
            dashboardTried = Display.INVALID_DISPLAY;
            ensureDashboard();
        }
        @Override public void onAppMessage(String message) {
            Toast.makeText(XDeskActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    private final AudioRoute.Listener audioListener = new AudioRoute.Listener() {
        @Override public void onOutputsChanged() {
            updateAudioChip();
            // 列表开着时设备变了：重开一次，内容才对
            if (outputDialog != null && outputDialog.isShowing()) {
                outputDialog.dismiss();
                chooseOutput();
            }
        }
        @Override public void onAudioMessage(String message) {
            Toast.makeText(XDeskActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    private final ChooserView.Host chooserHost = new ChooserView.Host() {
        @Override public void onDockTap(String key) { chooseKey(key); }
        @Override public void onItemLongPress(String key) { showAppCard(key); }
        @Override public void onDockToggle(String key) {
            List<String> dock = savedLines(PREF_DOCK);
            if (!dock.remove(key)) dock.add(key);
            saveDock(dock);
        }
        @Override public void onChooserClose() { closeChooserOverSession(); }
        @Override public void onOpenSettings() { showSettings(); }
        @Override public void onChooseOutput() { chooseOutput(); }
        @Override public void onSessionStop(String name) { confirmStop(); }
        @Override public void onSessionChosen(final String name) {
            if (externalDisplay() == null) {
                needDisplayNotice();
                return;
            }
            // 选择界面刚读过；从触摸板这边切换时可能已经过时，先问一下
            if (chooser.getVisibility() == View.VISIBLE) sessionDecided(name);
            else withFreshTermuxState(new Runnable() {
                @Override public void run() { sessionDecided(name); }
            });
        }
        private void sessionDecided(final String name) {
            rememberRecent(ChooserView.recentKey(name));
            if (name.equals(runningSession)) {
                if (session.isActive()) {
                    desktopName = name;
                    closeChooserOverSession();
                    updateStatus();
                } else {
                    reattach();
                }
            } else if (runningSession != null) {
                ui().confirm("切换桌面？", "会结束正在运行的 " + runningSession + "，未保存的内容会丢失。", "切换", new Runnable() {
                    @Override public void run() { launchDesktop(name, true); }
                });
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
            desktopName = null;
            if (!session.isActive()) attachDisplay(d);
            showWaiting();
        }
        @Override public void onAppChosen(AppCatalog.App app) {
            chooseApp(app);
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
        cover = new ExternalCover(this);
        prefs = getPreferences(MODE_PRIVATE);
        session = DesktopSession.get(this);
        apps = AppSession.get(this);
        catalog = AppCatalog.get(this);
        audioRoute = AudioRoute.get(this);
        if (prefs.contains(PREF_OUTPUT)) audioRoute.migrateOldChoice(prefs.getInt(PREF_OUTPUT, 0));
        applyOrientation();
        lastOrientation = getResources().getConfiguration().orientation;
        setContentView(buildUi());
        screens.getViewTreeObserver().addOnGlobalLayoutListener(this::fitToKeyboard);
        session.setListener(sessionListener);
        apps.setListener(appListener);
        audioRoute.setListener(audioListener);
        displays.registerDisplayListener(displayListener, ui);
        for (Display d : displays.getDisplays()) if (TargetDisplay.isReal(d)) realDisplays.add(d.getDisplayId());
        Display car = TargetDisplay.car(displays);
        if (car != null) carSeen = car.getDisplayId();
        CarActivity.setListener(this::carChanged);
        final Context app = getApplicationContext();
        MainActivity.displayAllowed = id -> TargetDisplay.allowsDesktop(app, id);
        inputs.registerInputDeviceListener(deviceListener, ui);
        tryStart();
        ensureDashboard();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ensureDashboard();
        apps.activateIfPaired();
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
        Display d = desktopDisplay();
        if (needsPlacement && d != null) {
            needsPlacement = false;
            placeX11(d.getDisplayId());
            updateStatus();
        }
    }

    /** 手机切到别的应用、或者锁屏：外接屏上的内容盖起来，键鼠交给手机。回来时都恢复。车机上不盖：Android Auto 的屏上未必能放悬浮窗。 */
    @Override protected void onStop() {
        super.onStop();
        Display d = externalDisplay();
        if (d == null || TargetDisplay.isCar(d) || !prefs.getBoolean(PREF_COVER, true) || !(apps.isActive() || session.isActive())) return;
        cover.show(d);
        if (cover.isShown()) {
            forwardingBeforeCover = apps.isForwarding();
            if (forwardingBeforeCover) apps.setForwarding(false);
            apps.setHotkeyAllowed(false);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        removeCover();
    }

    private void removeCover() {
        if (!cover.isShown()) return;
        cover.hide();
        apps.setHotkeyAllowed(true);
        if (forwardingBeforeCover) apps.setForwarding(true);
        forwardingBeforeCover = false;
        if (phonePassive) apps.focusExternal();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        // onStop took the hotkey and the input away for the cover. The session outlives this window, so give them back
        removeCover();
        displays.unregisterDisplayListener(displayListener);
        CarActivity.setListener(null);
        inputs.unregisterInputDeviceListener(deviceListener);
        ui.removeCallbacksAndMessages(null);
        session.setListener(null);
        apps.setListener(null);
        audioRoute.setListener(null);
        super.onDestroy();
    }


    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            if (padFullscreen) applyImmersive();
            // 选择界面开着时焦点归搜索框
            if (chooser.getVisibility() != View.VISIBLE) keyView.requestFocus();
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

        setupSecond = Style.button(this, "", Style.SURFACE_RAISED, 14, 24);
        setupSecond.setPadding(dp(28), dp(10), dp(28), dp(10));
        setupSecond.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(12);
        s.addView(setupSecond, sp);

        setupBack = Style.button(this, "回到启动器", Style.SURFACE_RAISED, 14, 24);
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
        latches.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BACKGROUND);
        root.setPadding(dp(Style.EDGE), dp(4), dp(Style.EDGE), dp(Style.EDGE));

        statusDot = new View(this);
        statusText = Style.label(this, "", Style.TEXT, Style.TEXT_SMALL + 1);
        statusText.setMaxLines(2);
        statusText.setPadding(dp(8), 0, dp(8), 0);
        buildTopButtons();
        runningDock = new DockBar(this, chooserHost, !landscape, false);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (landscape) {
            // 横屏高度最值钱：状态、收藏和按钮共一行
            topBar = top;
            top.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            top.addView(statusText, new LinearLayout.LayoutParams(dp(128), -2));
            top.addView(runningDock, new LinearLayout.LayoutParams(0, dp(Style.TARGET), 1));
            addTopButtons(top, false);
            root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        } else {
            // 竖屏放不下一行，状态单独占一行
            topBar = null;
            LinearLayout status = new LinearLayout(this);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(dp(4), dp(2), 0, dp(4));
            status.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            status.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(status, new LinearLayout.LayoutParams(-1, -2));
            addTopButtons(top, true);
            root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        }

        diagText = Style.label(this, "", Style.TEXT_DIM, Style.TEXT_TINY + 1);
        diagText.setPadding(dp(8), 0, dp(8), dp(4));
        root.addView(diagText, new LinearLayout.LayoutParams(-1, -2));
        applyDiagVisibility();

        if (!landscape) {
            LinearLayout.LayoutParams dock = new LinearLayout.LayoutParams(-1, dp(62));
            dock.topMargin = dp(4);
            root.addView(runningDock, dock);
        }

        View center = buildTouchArea();
        backBtn = new Style.IconButton(this, R.drawable.xd_ic_back, "返回 App", Style.SURFACE);
        // 安卓的 Back，给外接屏上的应用。手机上它是边缘滑动，有些应用不留返回按钮
        backBtn.onTap(new Runnable() {
            @Override public void run() { apps.sendBack(); }
        });
        Style.IconButton keyboard = new Style.IconButton(this, R.drawable.xd_ic_keyboard, "键盘", Style.SURFACE);
        keyboardBtn = keyboard;
        keyboard.onTap(new Runnable() {
            @Override public void run() { toggleKeyboard(); }
        });
        // 不盖在触控区上：触控模式下那里的每个点都对应外接屏
        keysHandle = new Style.IconButton(this, R.drawable.xd_ic_chevron_down, "功能键", Style.SURFACE);
        keysHandle.onTap(new Runnable() {
            @Override public void run() {
                keysExpanded = !keysExpanded;
                applyKeysVisibility();
            }
        });
        leftButton = mouseButton("左键", 1);
        rightButton = mouseButton("右键", 3);
        int gap = dp(Style.GAP);
        if (landscape) {
            // 左右键在触控区两边，两只手正好够得到。Back 和键盘各在一边的底下
            LinearLayout middle = new LinearLayout(this);
            leftColumn = sideColumn(leftButton, backBtn, keysHandle);
            rightColumn = sideColumn(rightButton, keyboard);
            middle.addView(leftColumn, new LinearLayout.LayoutParams(dp(SIDE_WIDTH), -1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
            cp.leftMargin = cp.rightMargin = gap;
            middle.addView(center, cp);
            middle.addView(rightColumn, new LinearLayout.LayoutParams(dp(SIDE_WIDTH), -1));
            LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(-1, 0, 1);
            mid.topMargin = gap;
            root.addView(middle, mid);
        } else {
            leftColumn = rightColumn = null;
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1);
            tp.topMargin = gap;
            root.addView(center, tp);
            // 左右键紧贴触控区下面，一左一右；Back 和键盘在中间
            LinearLayout bottom = new LinearLayout(this);
            bottom.setGravity(Gravity.CENTER_HORIZONTAL);
            bottom.addView(leftButton, bottomParams(1));
            for (View b : new View[]{backBtn, keyboard, keysHandle}) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(Style.TARGET + 4), -1);
                p.leftMargin = p.rightMargin = dp(2);
                bottom.addView(b, p);
            }
            bottom.addView(rightButton, bottomParams(1));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(64));
            bp.topMargin = gap;
            root.addView(bottom, bp);
        }
        updateAudioChip();
        applyModeToTopBar();
        applyKeysVisibility();
        refreshRunningDock();
        return padFullscreen ? fullscreenRoot(center) : root;
    }

    /**
     * 只留触控区和角上半透明的小图标：退出，返回 App，键盘。它们收下自己那里的触摸。其余的控件还在，只是不显示。
     * 键鼠模式下左右键也不显示：轻点、双指轻点和长按拖动都能代替。
     */
    private View fullscreenRoot(View center) {
        ((android.view.ViewGroup) center.getParent()).removeView(center);
        FrameLayout full = new FrameLayout(this);
        full.setBackgroundColor(Style.BACKGROUND);
        full.addView(center, new FrameLayout.LayoutParams(-1, -1));
        fullscreenBtn.setVisibility(View.GONE);
        addCornerIcon(full, R.drawable.xd_ic_close, "退出全屏", Gravity.TOP | Gravity.END, new Runnable() {
            @Override public void run() { setFullscreenWanted(false); }
        });
        if (!apps.isActive()) {
            addCornerIcon(full, R.drawable.xd_ic_keyboard, "键盘", Gravity.BOTTOM | Gravity.END, new Runnable() {
                @Override public void run() { toggleKeyboard(); }
            });
        }
        if (apps.isActive()) {
            addCornerIcon(full, R.drawable.xd_ic_back, "返回 App", Gravity.TOP | Gravity.START, new Runnable() {
                @Override public void run() { apps.sendBack(); }
            });
        }
        // 系统只认每条边最靠下的 200dp，手势条也排除不了。这里能拦多少算多少
        full.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
            v.setSystemGestureExclusionRects(java.util.Collections.singletonList(new android.graphics.Rect(0, 0, r - l, b - t))));
        return full;
    }

    private void addCornerIcon(FrameLayout parent, int icon, String label, int gravity, Runnable action) {
        Style.IconButton b = new Style.IconButton(this, icon, "", Style.SURFACE);
        b.setAlpha(0.5f);
        b.setContentDescription(label);
        b.onTap(action);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET));
        p.gravity = gravity;
        p.leftMargin = p.topMargin = p.rightMargin = p.bottomMargin = dp(4);
        parent.addView(b, p);
    }

    /** 全屏是触控区的属性，不分操作方式，也不分应用。 */
    private void setFullscreenWanted(boolean on) {
        prefs.edit().putBoolean(PREF_FULL_PAD, on).apply();
        applyFullscreen();
    }

    /** 只有控制界面在最前面时才全屏，启动器和设置屏都要看得到系统栏。 */
    private void applyFullscreen() {
        updatePhoneFocus();
        boolean running = (apps.isActive() && apps.isStarted()) || session.isActive();
        boolean want = running && chooser != null && chooser.getVisibility() != View.VISIBLE
            && setupScreen.getVisibility() != View.VISIBLE && prefs.getBoolean(PREF_FULL_PAD, false);
        if (want == padFullscreen) return;
        padFullscreen = want;
        rebuildRunning();
        applyImmersive();
    }

    private void applyImmersive() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (padFullscreen) {
            // 从边上滑一下系统栏临时出来，又自己收回去
            c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.systemBars());
        } else {
            c.show(WindowInsets.Type.systemBars());
        }
    }

    private void rebuildRunning() {
        releaseLatches();
        directTouch.cancel();
        screens.removeView(runningScreen);
        runningScreen = buildRunningScreen();
        screens.addView(runningScreen, 0, new FrameLayout.LayoutParams(-1, -1));
        updateStatus();
        if (chooser.getVisibility() != View.VISIBLE) keyView.requestFocus();
        updateCapture();
    }

    private LinearLayout sideColumn(View big, View... small) {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.addView(big, new LinearLayout.LayoutParams(-1, 0, 1));
        for (View s : small) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
            p.topMargin = dp(Style.GAP);
            side.addView(s, p);
        }
        return side;
    }

    private void buildTopButtons() {
        audioBtn = new Style.IconButton(this, R.drawable.xd_ic_sound, "", Style.SURFACE);
        audioBtn.onTap(new Runnable() {
            @Override public void run() { chooseOutput(); }
        });
        touchBtn = new Style.IconButton(this, R.drawable.xd_ic_pointer, "键鼠", Style.SURFACE);
        touchBtn.onTap(new Runnable() {
            @Override public void run() {
                apps.toggleTouchMode();
                updateStatus();
            }
        });
        forwardBtn = new Style.IconButton(this, R.drawable.xd_ic_mouse, "接管", Style.SURFACE);
        forwardBtn.onTap(new Runnable() {
            @Override public void run() { apps.setForwarding(!apps.isForwarding()); }
        });
        launcherBtn = new Style.IconButton(this, R.drawable.xd_ic_apps, "启动器", Style.SURFACE);
        launcherBtn.onTap(new Runnable() {
            @Override public void run() { openChooserOverSession(); }
        });
        settingsBtn = new Style.IconButton(this, R.drawable.xd_ic_settings, "设置", Style.SURFACE);
        settingsBtn.onTap(new Runnable() {
            @Override public void run() { showSettings(); }
        });
    }

    private void addTopButtons(LinearLayout row, boolean fill) {
        for (View b : new View[]{audioBtn, touchBtn, forwardBtn, launcherBtn, settingsBtn}) {
            LinearLayout.LayoutParams p = fill ? new LinearLayout.LayoutParams(0, dp(Style.TARGET + 4), 1)
                : new LinearLayout.LayoutParams(dp(Style.TARGET + 4), dp(Style.TARGET));
            p.leftMargin = fill ? dp(2) : dp(4);
            if (fill) p.rightMargin = dp(2);
            row.addView(b, p);
        }
    }

    /** 功能键（默认收起）和触控区。触摸板和触控面在同一个位置，看模式显示哪个。 */
    private View buildTouchArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        LinearLayout keys = new LinearLayout(this);
        keysRow = keys;
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
        // 系统键盘的字直接交给应用，加不上这里锁定的修饰键，所以把常用组合的字母放在这里
        LinearLayout combos = new LinearLayout(this);
        comboRow = combos;
        combos.setVisibility(View.GONE);
        combos.addView(keyButton("A 全选", SCAN_A), keyParams());
        combos.addView(keyButton("X 剪切", SCAN_X), keyParams());
        combos.addView(keyButton("C 复制", SCAN_C), keyParams());
        combos.addView(keyButton("V 粘贴", SCAN_V), keyParams());
        combos.addView(keyButton("Z 撤销", SCAN_Z), keyParams());
        LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(-1, dp(Style.TARGET));
        cr.bottomMargin = dp(Style.GAP);
        area.addView(combos, cr);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, dp(Style.TARGET));
        kp.bottomMargin = dp(Style.GAP);
        area.addView(keys, kp);

        FrameLayout frame = new FrameLayout(this);
        touchpad = new TouchpadView(this, output);
        touchpad.setSpeed(prefs.getFloat(PREF_SPEED, 1f));
        touchpad.setScrollSpeed(prefs.getFloat(PREF_SCROLL_SPEED, 1f));
        frame.addView(touchpad, new FrameLayout.LayoutParams(-1, -1));
        directTouch = new DirectTouchView(this, new DirectTouchView.Sink() {
            @Override public void moveCursor(int dx, int dy) { apps.input().move(dx, dy); }
            @Override public void tap() { apps.input().click(1); }
            @Override public void press(boolean down) { apps.input().button(1, down); }
            @Override public void frame(int action, int[] ids, float[] x, float[] y) { apps.touchFrame(action, ids, x, y); }
        });
        directTouch.setSpeed(prefs.getFloat(PREF_SPEED, 1f));
        frame.addView(directTouch, new FrameLayout.LayoutParams(-1, -1));
        // 全屏是触控区自己的事，图标放在它的角上
        Style.IconButton enter = new Style.IconButton(this, R.drawable.xd_ic_fullscreen, "", Style.SURFACE_RAISED);
        enter.setAlpha(0.7f);
        enter.setContentDescription("触控区全屏");
        enter.onTap(new Runnable() {
            @Override public void run() { setFullscreenWanted(true); }
        });
        fullscreenBtn = enter;
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET));
        ep.gravity = Gravity.TOP | Gravity.END;
        ep.topMargin = ep.rightMargin = dp(4);
        frame.addView(enter, ep);
        area.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        return area;
    }

    /** 收起时修饰键的锁定还在。键盘弹出时自动展开，收起后回到用户选的样子。 */
    private void applyKeysVisibility() {
        if (keysRow == null) return;
        boolean show = (keysExpanded || keyboardOpen) && !padFullscreen;
        keysRow.setVisibility(show ? View.VISIBLE : View.GONE);
        applyComboRow();
        keysHandle.setIcon(show ? R.drawable.xd_ic_chevron_up : R.drawable.xd_ic_chevron_down);
    }

    /** 转屏不重建 Activity（窗口和输入都不能断），只换一套布局。 */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (c.orientation == lastOrientation) return;
        lastOrientation = c.orientation;
        rebuildRunning();
        applyImmersive();
    }

    /** 键盘弹出：展开功能键。横屏高度很紧，再收起顶部一行。 */
    private void fitToKeyboard() {
        WindowInsets w = screens.getRootWindowInsets();
        boolean open = w != null && w.isVisible(WindowInsets.Type.ime());
        if (open != keyboardOpen) {
            keyboardOpen = open;
            applyKeysVisibility();
        }
        if (topBar == null) return;
        int topVisibility = open ? View.GONE : View.VISIBLE;
        if (topBar.getVisibility() != topVisibility) {
            topBar.setVisibility(topVisibility);
            if (open) diagText.setVisibility(View.GONE);
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
        applyComboRow();
    }

    /** Ctrl、Alt 这些锁定时才显示。桌面的键盘文字会经过这里，用不着。 */
    private void applyComboRow() {
        if (comboRow == null) return;
        boolean show = apps.isActive() && keysRow.getVisibility() == View.VISIBLE;
        if (show) {
            show = false;
            for (Latch l : latches) show |= l.on;
        }
        comboRow.setVisibility(show ? View.VISIBLE : View.GONE);
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

    /**
     * 外接屏上跑着应用时，手机窗口不拿焦点。摸手机屏会把焦点从外接屏抢过来，外接屏上的应用因此清掉选中的文字、收起键盘。
     * 不拿焦点时触摸照常收到。启动器的搜索框要焦点，那时才放开。
     */
    private void updatePhoneFocus() {
        if (chooser == null || setupScreen == null) return;
        boolean passive = apps.isActive() && chooser.getVisibility() != View.VISIBLE
            && setupScreen.getVisibility() != View.VISIBLE;
        if (passive == phonePassive) return;
        phonePassive = passive;
        if (passive) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            apps.focusExternal();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
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

    /** 桌面的键鼠走 Pointer Capture，没有接管这回事。 */
    private void applyModeToTopBar() {
        updatePhoneFocus();
        boolean app = apps.isActive();
        forwardBtn.setVisibility(app ? View.VISIBLE : View.GONE);
        backBtn.setVisibility(app ? View.VISIBLE : View.GONE);
        // 安卓应用要键盘时输入法自己会出来，也有自己的收起键。这个按钮只有桌面要用
        keyboardBtn.setVisibility(app ? View.GONE : View.VISIBLE);
        touchBtn.setVisibility(apps.canUseTouchMode() ? View.VISIBLE : View.GONE);
        applyInputMode();
    }

    /**
     * 键鼠：触摸板加左右键。触控：手指直接到外接屏上，这时不用鼠标键，触控区更大。实体鼠标在两种模式下都能用，
     * 触控模式下左键是一根手指，右键是两根，滚轮是滑动。
     */
    private void applyInputMode() {
        boolean touch = apps.isActive() && apps.isTouchMode();
        if (!touch) directTouch.cancel();
        directTouch.setVisibility(touch ? View.VISIBLE : View.GONE);
        touchpad.setVisibility(touch ? View.GONE : View.VISIBLE);
        leftButton.setVisibility(touch ? View.GONE : View.VISIBLE);
        rightButton.setVisibility(touch ? View.GONE : View.VISIBLE);
        for (View side : new View[]{leftColumn, rightColumn}) {
            if (side != null) side.getLayoutParams().width = dp(touch ? SIDE_WIDTH_TOUCH : SIDE_WIDTH);
        }
        // 右边这一列只放右键和键盘按钮，这时都不显示
        if (rightColumn != null && touch) rightColumn.getLayoutParams().width = 0;
        if (leftColumn != null) leftColumn.requestLayout();
        if (rightColumn != null) rightColumn.requestLayout();
        int[] size = apps.displaySize();
        if (size != null) directTouch.setDisplaySize(size[0], size[1]);
    }

    private void updateAppStatus() {
        forwardBtn.setLabel(apps.isForwarding() ? "交还" : "接管");
        forwardBtn.setActive(apps.isForwarding());
        boolean touch = apps.isTouchMode();
        touchBtn.setLabel(touch ? "触控" : "键鼠");
        touchBtn.setIcon(touch ? R.drawable.xd_ic_touch : R.drawable.xd_ic_pointer);
        touchBtn.setActive(touch);
        if (apps.hasInjector() && apps.hasDisplay() && apps.app() == null) {
            setStatus(Style.WARN, "外接屏上没有应用了，选一个");
        } else if (!apps.hasInjector()) {
            setStatus(Style.DANGER, apps.usesShell() ? "注入服务断开了，打开无线调试后回到 XDesk" : "注入服务断开了，确认 Shizuku 还在运行");
        } else if (!apps.hasDisplay()) {
            setStatus(Style.WARN, "外接屏已断开，插回后 " + apps.label() + " 自动回去");
        } else if (apps.isForwarding() && apps.deviceCount() == 0) {
            setStatus(Style.WARN, apps.label() + "：没有外接键鼠，插上后接管");
        } else if (apps.isForwarding()) {
            setStatus(Style.OK, apps.label() + " · 键鼠在外接屏");
        } else {
            setStatus(Style.OK, apps.label() + " · 键鼠在手机");
        }
    }

    private void updateStatus() {
        audioRoute.setCar(onCar());
        applyFullscreen();
        applyModeToTopBar();
        if (apps.isActive()) {
            updateAppStatus();
            return;
        }
        if (session.isConnected()) setStatus(Style.OK, desktopName != null ? (onCar() ? "车机上：" : "外接屏上：") + desktopName : "桌面已连接");
        else setStatus(Style.WARN, "桌面未连接");
    }

    private void showSetup(String title, String message, boolean progress, String action, View.OnClickListener onAction) {
        chooser.setVisibility(View.GONE);
        chooser.setNotice(null);
        setupScreen.setVisibility(View.VISIBLE);
        applyFullscreen();
        setupTitle.setText(title);
        setupMessage.setText(message);
        setupProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
        setupAction.setVisibility(action != null ? View.VISIBLE : View.GONE);
        if (action != null) {
            setupAction.setText(action);
            setupAction.setOnClickListener(onAction);
        }
        setupSecond.setVisibility(View.GONE);
    }

    /** 紧接着 showSetup 调用：多一个次要的选择。 */
    private void addSetupChoice(String action, View.OnClickListener onAction) {
        setupSecond.setText(action);
        setupSecond.setOnClickListener(onAction);
        setupSecond.setVisibility(View.VISIBLE);
    }

    private void showRunning() {
        if (chooser.getVisibility() == View.VISIBLE) {
            ime.hideSoftInputFromWindow(chooser.getWindowToken(), 0);
            chooser.clearSearch();
        }
        chooser.setVisibility(View.GONE);
        chooser.setNotice(null);
        setupScreen.setVisibility(View.GONE);
        applyFullscreen();
        refreshDocks();
        keyView.requestFocus();
        updateCapture();
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
        if (apps.isActive() && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            apps.phoneTouched();
            // 设置页这类窗口关掉后，焦点还留在手机屏
            if (phonePassive) apps.focusExternal();
        }
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

    private void updateAudioChip() {
        String label = audioRoute.shortLabel();
        if (audioBtn != null) audioBtn.setLabel(label);
        if (settingsOutputValue != null) settingsOutputValue.setText(label);
        if (chooser != null) chooser.setOutputLabel(label);
    }

    /** 设备列表每次打开时读，开着时变了由 audioListener 重开。 */
    private void chooseOutput() {
        if (audioRoute.isCar()) {
            Toast.makeText(this, "车机上声音经 Android Auto 从车里播放，不用选输出", Toast.LENGTH_LONG).show();
            return;
        }
        final List<AudioRoute.Output> outputs = audioRoute.outputs();
        String[] labels = new String[outputs.size() + 1];
        labels[0] = "系统默认（不指定）";
        String selected = audioRoute.selectedKey();
        int checked = selected.isEmpty() ? 0 : -1;
        int selectedType = selected.isEmpty() ? -1 : Integer.parseInt(selected.substring(0, selected.indexOf('|')));
        for (int i = 0; i < outputs.size(); i++) {
            AudioRoute.Output o = outputs.get(i);
            labels[i + 1] = o.label;
            if (o.key.equals(selected) || (checked < 0 && o.type == selectedType)) checked = i + 1;
        }
        String note = (apps.injectorReady()
            ? "所有模式共用：会话进行时 Termux 桌面、安卓应用和其他媒体声音都走这里，切换也不变。结束 XDesk 后手机恢复正常，下次再按这里的选择。"
            : "注入服务没有运行：只能尽力指定 Termux 桌面的声音，接着 HDMI 时可能不起作用，安卓应用的声音也不受影响。")
            + "\n只是把输出指向这个设备，音质和蓝牙编码没法确认。";
        outputDialog = ui().choice("声音从哪里播放", note, labels, checked, which -> {
            audioRoute.select(which == 0 ? AudioRoute.KEY_SYSTEM : outputs.get(which - 1).key);
            if (audioRoute.isMissing()) setStatus(Style.WARN, "现在没有这个输出设备，暂时走系统默认");
        });
    }

    private SettingsUi ui() {
        return new SettingsUi(this);
    }

    /** 只影响 XDesk 的触控界面：手机系统锁竖屏时这里也能横过来。默认跟随系统。 */
    private void applyOrientation() {
        setRequestedOrientation(ORIENTATIONS[prefs.getInt(PREF_ORIENTATION, ORIENTATION_DEFAULT)]);
    }

    private int widthIndex() {
        int w = prefs.getInt(PREF_WIDTH, DEFAULT_WIDTH);
        for (int i = 0; i < WIDTHS.length; i++) if (WIDTHS[i] == w) return i;
        return 1;
    }

    private void chooseResolution(final TextView value) {
        ui().choice("桌面分辨率", null, WIDTH_LABELS, widthIndex(), which -> {
            prefs.edit().putInt(PREF_WIDTH, WIDTHS[which]).apply();
            Display d = externalDisplay();
            if (d != null) applySize(d);
            value.setText(WIDTH_LABELS[which]);
        });
    }

    /** 外接屏界面大小的默认值。应用可以在长按卡片里有自己的值。 */
    private void addDensitySetting(SettingsUi s, LinearLayout card) {
        if (!apps.canSetDensity()) {
            String why = session.isActive() ? "Termux 桌面显示时不改，它有自己的 DPI 设置，这时外接屏用默认值"
                : !apps.injectorReady() ? "需要注入服务" : "外接屏上有安卓应用时才能改";
            s.infoRow(card, "界面大小", why, null);
            return;
        }
        String own = apps.defaultDensity() > 0 ? String.valueOf(apps.defaultDensity()) : "未知";
        s.numberRow(card, "界面大小", DENSITY_MIN + " 到 " + DENSITY_MAX + " dpi，越小内容越多。显示器默认 " + own,
            apps.defaultDensity() > 0 ? own : "dpi", apps.savedDensity(), DENSITY_MIN, DENSITY_MAX,
            dpi -> apps.setDensity(dpi), "恢复默认", () -> apps.setDensity(0));
    }

    private void showSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        boolean appMode = apps.isActive();
        final Dialog[] page = new Dialog[1];

        LinearLayout show = s.section(c, "显示");
        int orientation = 2 - prefs.getInt(PREF_ORIENTATION, ORIENTATION_DEFAULT);
        s.segmentedRow(show, "触控界面方向", null, ORIENTATION_LABELS, orientation, which -> {
            prefs.edit().putInt(PREF_ORIENTATION, ORIENTATION_VALUES[which]).apply();
            applyOrientation();
        });
        s.switchRow(show, "闲置时调暗手机屏", "1 分钟没碰就调暗，外接屏不受影响", prefs.getBoolean(PREF_DIM, true), on -> {
            prefs.edit().putBoolean(PREF_DIM, on).apply();
            bumpIdle();
        });

        LinearLayout input = s.section(c, "输入");
        s.percentRow(input, "触摸板灵敏度", null, Math.round(prefs.getFloat(PREF_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            touchpad.setSpeed(v);
            directTouch.setSpeed(v);
            prefs.edit().putFloat(PREF_SPEED, v).apply();
        });
        s.percentRow(input, "双指滚动速度", "键鼠模式的触摸板", Math.round(prefs.getFloat(PREF_SCROLL_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            touchpad.setScrollSpeed(v);
            prefs.edit().putFloat(PREF_SCROLL_SPEED, v).apply();
        });
        if (appMode) {
            s.percentRow(input, "实体鼠标速度", "100% 按显示器宽度自动算", Math.round(prefs.getFloat(PREF_MOUSE_SPEED, 1f) * 100), p -> {
                float v = p / 100f;
                prefs.edit().putFloat(PREF_MOUSE_SPEED, v).apply();
                apps.setMouseSpeed(v);
            });
            s.switchRow(input, "始终显示 XDesk 光标", "RustDesk 会话里判断错时打开", prefs.getBoolean(PREF_ALWAYS_CURSOR, false), on -> {
                prefs.edit().putBoolean(PREF_ALWAYS_CURSOR, on).apply();
                apps.setAlwaysShowCursor(on);
            });
        }

        LinearLayout sound = s.section(c, "声音");
        settingsOutputValue = s.valueRow(sound, "声音输出", null, audioRoute.shortLabel(), this::chooseOutput);

        LinearLayout external = s.section(c, "外屏");
        addDensitySetting(s, external);
        if (AppSession.fullscreenSupported()) {
            s.switchRow(external, "应用强制横屏全屏", "忽略竖屏锁定，在外屏和其他大屏上都生效，手机屏不受影响。应用下次启动时生效，第一次会重启。可在应用设置里单独改", apps.isFullscreenDefault(), on -> apps.setFullscreenDefault(on));
            s.valueRow(external, "恢复所有应用", "去掉 XDesk 给应用设过的横屏全屏", "", () ->
                s.confirm("恢复所有应用？", "XDesk 设过横屏全屏的应用都恢复原样，它们会被系统结束一次。默认和每个应用的选择也回到关。", "恢复", () -> {
                    if (!apps.resetFullscreen()) Toast.makeText(this, "要先连上注入服务：运行一次安卓应用后再试", Toast.LENGTH_LONG).show();
                }));
        }
        String cover = "外屏显示时钟，键鼠交给手机。应用继续运行，声音不停";
        if (!Settings.canDrawOverlays(this)) cover += "。没有悬浮窗权限，现在不会遮住";
        s.switchRow(external, "切到其他应用或锁屏时遮住外屏", cover, prefs.getBoolean(PREF_COVER, true), on -> {
            prefs.edit().putBoolean(PREF_COVER, on).apply();
        });
        if (!appMode) {
            final TextView[] resolution = new TextView[1];
            resolution[0] = s.valueRow(external, "桌面分辨率", "Termux 桌面的 X 分辨率", WIDTH_LABELS[widthIndex()], () -> chooseResolution(resolution[0]));
        }

        LinearLayout about = s.section(c, "关于");
        s.infoRow(about, "XDesk", null, versionName());
        s.switchRow(about, "显示诊断信息", "在控制界面上显示焦点和事件计数", prefs.getBoolean(PREF_DIAG, false), on -> {
            prefs.edit().putBoolean(PREF_DIAG, on).apply();
            applyDiagVisibility();
        });
        final ShellActivation shell = apps.shell();
        if (shell.isPaired() || shell.isRunning()) {
            s.infoRow(about, "内置注入服务", "没有 Shizuku 时，安卓应用的键鼠靠它", shellState(shell));
            if (shell.isRunning()) {
                s.valueRow(about, "停止内置服务", "XDesk 不再自己启动它，下次运行安卓应用时再启动", "", () ->
                    s.confirm("停止内置服务？", "用它运行的安卓应用回到手机（不关闭），键鼠和声音恢复正常。", "停止", () -> {
                        page[0].dismiss();
                        stopShellServer(null);
                    }));
            }
            s.valueRow(about, "重新配对", "在开发者选项里撤销过授权，或一直连不上时用", "", () ->
                s.confirm("重新配对？", "XDesk 会停止内置服务并忘掉配对，然后带你在无线调试里重新配对。", "重新配对", () -> {
                    page[0].dismiss();
                    stopShellServer(() -> {
                        shell.forgetPairing();
                        retryRequested = true;
                        activationFlow = true;
                        showActivation(null);
                    });
                }));
        }

        if (session.isActive()) {
            s.dangerRow(c, "结束 Termux 桌面", "XDesk 不退出", () -> {
                page[0].dismiss();
                confirmStop();
            });
        }
        if (DesktopService.isRunning()) {
            s.dangerRow(c, "结束 XDesk", "应用回到手机，外接屏恢复镜像", () -> {
                page[0].dismiss();
                confirmEndXDesk();
            });
        }
        page[0] = s.page("设置", c);
        page[0].setOnDismissListener(d -> settingsOutputValue = null);
    }

    private static String shellState(ShellActivation shell) {
        if (shell.isRunning()) return "运行中";
        if (shell.isStarting()) return "正在启动";
        return shell.isPaired() ? "没有运行" : "未配对";
    }

    /**
     * 停止内置服务。用它运行的安卓应用先回到手机，和结束 XDesk 时一样；服务结束前也会交还它占着的东西。
     * @param then 应用移完、服务停下之后在主线程运行，可以是 null
     */
    private void stopShellServer(final Runnable then) {
        final ShellActivation shell = apps.shell();
        final Runnable stop = () -> {
            shell.stopServer();
            if (then != null) then.run();
        };
        if (!apps.usesShell() || !apps.hasInjector()) {
            stop.run();
            return;
        }
        boolean appOnDisplay = apps.isActive();
        Display real = TargetDisplay.real(displays);
        if (appOnDisplay) {
            releaseLatches();
            client = null;
        }
        apps.endSession(appOnDisplay && real != null ? real.getDisplayId() : -1, () -> {
            stop.run();
            dashboardTried = Display.INVALID_DISPLAY;
            ensureDashboard();
            DesktopService.stopIfIdle(this);
        });
        if (appOnDisplay) backToChooser();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
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
            if (session.isActive() && chooser.getVisibility() != View.VISIBLE && hasExternalMouse()) keyView.requestPointerCapture();
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

    /** 现在用哪个屏：外接屏优先，没有就是车机（TargetDisplay）。运行中的桌面所在的屏还在，就一直是它。 */
    private Display externalDisplay() {
        if (session.isActive()) {
            Display on = desktopDisplay();
            if (on != null) return on;
        }
        return TargetDisplay.find(displays);
    }

    /** 运行中的桌面放在的那种屏，断开了是 null。 */
    private Display desktopDisplay() {
        return desktopOnCar ? TargetDisplay.car(displays) : TargetDisplay.real(displays);
    }

    /** 车机来了或走了（CarActivity 起来、关掉，或者它的显示器没了）。外接屏优先，桌面在外接屏上时车机怎样都不管。 */
    private void carChanged() {
        Display car = TargetDisplay.car(displays);
        int id = car != null ? car.getDisplayId() : Display.INVALID_DISPLAY;
        if (id == carSeen) return;
        carSeen = id;
        audioRoute.setCar(onCar());
        if (!session.isActive() || !desktopOnCar) {
            updateChooserDisplay();
            return;
        }
        if (car == null) {
            // 和拔掉外接屏一样：X 和桌面继续运行
            setStatus(Style.WARN, "车机已断开。" + CAR_GONE);
            needsPlacement = true;
            refocusSelf();
            return;
        }
        applySize(car);
        placeX11(id);
        needsPlacement = !resumed;
        updateStatus();
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
        chooser.setOverSession(false);
        chooser.setVisibility(View.VISIBLE);
        applyFullscreen();
        keyView.releasePointerCapture();
        updateAudioChip();
        refreshChooser();
    }

    /** 不停会话，把完整列表盖在触摸板上，用来切换到不在 Dock 里的东西。 */
    private void openChooserOverSession() {
        setupScreen.setVisibility(View.GONE);
        chooser.setOverSession(true);
        chooser.setVisibility(View.VISIBLE);
        applyFullscreen();
        keyView.releasePointerCapture();
        refreshChooser();
    }

    private void closeChooserOverSession() {
        showRunning();
    }

    @Override public void onBackPressed() {
        if (padFullscreen) setFullscreenWanted(false);
        else if (chooser.getVisibility() == View.VISIBLE && (apps.isActive() || session.isActive())) closeChooserOverSession();
        else super.onBackPressed();
    }

    /** 外接屏上现在是什么，和 Dock、最近使用同一种写法。 */
    private String currentKey() {
        AppCatalog.App a = apps.app();
        if (a != null) return ChooserView.recentKey(a);
        return session.isActive() && desktopName != null ? ChooserView.recentKey(desktopName) : null;
    }

    /** 上一个应用：现在这个之前用的那个；什么都没在运行时就是最后用的那个。 */
    private String previousKey() {
        String current = currentKey();
        for (String key : savedRecents()) {
            if (key.equals(current)) continue;
            if (!ChooserView.isAppKey(key) || catalog.find(ChooserView.componentOf(key)) != null) return key;
        }
        return null;
    }

    private void refreshRunningDock() {
        if (runningDock != null) runningDock.setItems(savedLines(PREF_DOCK), previousKey(), currentKey());
    }

    private void refreshDocks() {
        refreshRunningDock();
        chooser.setDock(savedLines(PREF_DOCK), previousKey(), currentKey());
    }

    private void saveDock(List<String> dock) {
        prefs.edit().putString(PREF_DOCK, String.join("\n", dock)).apply();
        refreshDocks();
    }

    private void moveInDock(String key, int by) {
        List<String> dock = savedLines(PREF_DOCK);
        int at = dock.indexOf(key), to = at + by;
        if (at < 0 || to < 0 || to >= dock.size()) return;
        dock.remove(at);
        dock.add(to, key);
        saveDock(dock);
    }

    /** 长按图标打开：这个应用记住的一切都在这里。Termux 环境只有 Dock 这部分。 */
    private void showAppCard(final String key) {
        final AppCatalog.App app = ChooserView.isAppKey(key) ? catalog.find(ChooserView.componentOf(key)) : null;
        if (ChooserView.isAppKey(key) && app == null) return;
        final SettingsUi s = ui();
        final Dialog[] card = new Dialog[1];
        final String name = app != null ? app.label : ChooserView.sessionOf(key);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int iconSize = dp(52);
        if (app != null) {
            ImageView icon = new ImageView(this);
            catalog.showIcon(app, icon);
            head.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        } else {
            TextView tile = Style.label(this, ">_", Style.TEXT, 18);
            tile.setTypeface(Typeface.MONOSPACE);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Style.rounded(this, Style.SURFACE_RAISED, 14));
            head.addView(tile, new LinearLayout.LayoutParams(iconSize, iconSize));
        }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Style.label(this, name, Style.TEXT, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(title);
        boolean onDisplay = key.equals(currentKey());
        TextView sub = Style.label(this, onDisplay ? (onCar() ? "正在车机上" : "正在外屏上") : app != null ? "安卓应用" : "Linux 桌面",
            onDisplay ? Style.OK : Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(12);
        head.addView(titles, tp);
        final ImageView star = new ImageView(this);
        int pad = dp(11);
        star.setPadding(pad, pad, pad, pad);
        star.setBackground(Style.ripple(this, Style.SURFACE, Style.RADIUS));
        star.setContentDescription("我的收藏");
        head.addView(star, new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET)));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        // 收藏里的位置：只有在收藏里才显示
        final LinearLayout order = s.card(c);
        LinearLayout orderRow = new LinearLayout(this);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(dp(12), dp(6), dp(8), dp(6));
        orderRow.addView(Style.label(this, "收藏里的位置", Style.TEXT, Style.TEXT_BODY), new LinearLayout.LayoutParams(0, -2, 1));
        TextView left = Style.button(this, "←", Style.SURFACE_RAISED, Style.TEXT_BODY + 1, Style.RADIUS);
        left.setOnClickListener(v -> {
            Style.tick(v);
            moveInDock(key, -1);
        });
        orderRow.addView(left, new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET - 8)));
        TextView right = Style.button(this, "→", Style.SURFACE_RAISED, Style.TEXT_BODY + 1, Style.RADIUS);
        right.setOnClickListener(v -> {
            Style.tick(v);
            moveInDock(key, 1);
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET - 8));
        rp.leftMargin = dp(6);
        orderRow.addView(right, rp);
        order.addView(orderRow, new LinearLayout.LayoutParams(-1, -2));

        final Runnable applyStar = () -> {
            boolean docked = savedLines(PREF_DOCK).contains(key);
            star.setImageResource(docked ? R.drawable.xd_ic_star : R.drawable.xd_ic_star_outline);
            star.setColorFilter(docked ? Style.WARN : Style.TEXT_DIM);
            order.setVisibility(docked ? View.VISIBLE : View.GONE);
        };
        applyStar.run();
        star.setOnClickListener(v -> {
            Style.tick(v);
            chooserHost.onDockToggle(key);
            applyStar.run();
        });

        if (app != null) {
            int global = apps.savedDensity() > 0 ? apps.savedDensity() : apps.defaultDensity();
            s.groupLabel(c, "外屏显示");
            LinearLayout display = s.card(c);
            s.inlineNumberRow(display, "界面大小", global > 0 ? "dpi，空着跟随默认 " + global : "dpi，空着跟随默认",
                global > 0 ? String.valueOf(global) : "默认", apps.appDensity(app), DENSITY_MIN, DENSITY_MAX,
                dpi -> apps.setAppDensity(app, dpi), () -> apps.setAppDensity(app, 0));
            if (AppSession.fullscreenSupported()) {
                s.segmentedRow(display, "横屏全屏", "默认现在是" + (apps.isFullscreenDefault() ? "开" : "关") + "。改动时应用会重启",
                    new String[]{"跟随默认", "开", "关"}, apps.appFullscreen(app), which -> apps.setAppFullscreen(app, which));
            }
            if (AppSession.touchModeAllowed(app)) {
                s.groupLabel(c, "输入");
                LinearLayout input = s.card(c);
                s.segmentedRow(input, "操作方式", "触控给只听触摸的应用或网页", new String[]{"键鼠", "触控"}, apps.isTouchMode(app) ? 1 : 0, which -> {
                    apps.setTouchMode(app, which == 1);
                    updateStatus();
                });
            }
            if (apps.canCloseApps()) {
                TextView close = Style.button(this, "关闭这个应用", Style.SURFACE, Style.TEXT_BODY, 16);
                close.setTextColor(Style.DANGER);
                close.setOnClickListener(v -> {
                    Style.tick(v);
                    s.confirm("关闭 " + app.label + "？", "未保存的内容可能会丢失。", "关闭", () -> {
                        card[0].dismiss();
                        apps.closeApp(app);
                    });
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(Style.TARGET));
                cp.topMargin = dp(16);
                c.addView(close, cp);
            }
        }
        card[0] = s.bottomSheet(head, c);
    }

    private void chooseKey(String key) {
        if (!ChooserView.isAppKey(key)) {
            chooserHost.onSessionChosen(ChooserView.sessionOf(key));
            return;
        }
        AppCatalog.App a = catalog.find(ChooserView.componentOf(key));
        if (a != null) chooseApp(a);
    }

    /** 已经有安卓应用在外接屏上就直接切换，否则走完整的启动流程。 */
    private void chooseApp(AppCatalog.App a) {
        if (apps.isActive() && apps.isStarted() && apps.hasDisplay() && apps.hasInjector()) {
            rememberRecent(ChooserView.recentKey(a));
            apps.switchTo(a);
            showRunning();
        } else {
            retryRequested = true;
            startApp(a);
        }
    }

    /** 问 Termux 现在跑着什么，答了或等不到时都接着走。 */
    private void withFreshTermuxState(final Runnable then) {
        if (!installed(PKG_TERMUX) || checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        final boolean[] done = new boolean[1];
        final Runnable once = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                then.run();
            }
        };
        boolean sent = TermuxCommand.run(this, TERMUX_STATE_COMMAND, new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) {
                parseTermuxState(stdout);
                once.run();
            }
        });
        if (sent) ui.postDelayed(once, TERMUX_SWITCH_WAIT_MS);
        else once.run();
    }

    private void refreshChooser() {
        updateActivationPrompt();
        updateChooserDisplay();
        refreshTermux();
        // 先用上次存的，马上就有；后台读完再更新
        chooser.setApps(catalog.apps());
        refreshDocks();
        catalog.refresh(new Runnable() {
            @Override public void run() {
                chooser.setApps(catalog.apps());
                refreshDocks();
            }
        });
    }

    private void updateChooserDisplay() {
        Display d = externalDisplay();
        chooser.setDisplayConnected(d != null, TargetDisplay.isCar(d));
        audioRoute.setCar(onCar());
    }

    private void needDisplayNotice() {
        Toast.makeText(this, "先用支持视频输出的 USB-C 线或扩展坞插上显示器", Toast.LENGTH_LONG).show();
    }

    /** 现在用的是车机，不是外接屏：说法和能做的事不同。车机断开时车机上的应用还在它的显示器上，还算车机。 */
    private boolean onCar() {
        return apps.isActive() ? apps.isOnCar() : TargetDisplay.isCar(externalDisplay());
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
        refreshDocks();
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
        setChooserTermux(savedSessions(), null, runningSession, false, null);
        boolean sent = TermuxCommand.run(this, TERMUX_STATE_COMMAND, new TermuxCommand.Callback() {
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

    /**
     * 离开设置和等待屏幕。外接屏不因此改变：有会话就把启动器盖在它上面。只取消还没成功的启动。
     * 结束会话只有 endXDesk 和从最近任务划掉。
     */
    private void backToChooser() {
        activationFlow = false;
        pendingApp = null;
        shizukuScreens = false;
        closeActivation();
        pendingSession = null;
        ui.removeCallbacks(connectTimeout);
        if (apps.isActive() && !apps.isStarted()) {
            apps.stop();
            showChooser();
        } else if (apps.isActive() || session.isConnected()) {
            openChooserOverSession();
        } else if (session.isActive()) {
            desktopGone();
        } else {
            showChooser();
        }
    }

    /**
     * 桌面窗口要消失：桌面结束了，或者等它连上时用户不等了。后面还留着安卓应用就接着用它，否则这次会话没东西了。
     * 这里不移动任何应用。
     */
    private void desktopGone() {
        ui.removeCallbacks(connectTimeout);
        releaseLatches();
        client = null;
        session.abandon();
        MainActivity m = MainActivity.getInstance("");
        if (m != null) m.finishAndRemoveTask();
        // 应用留在了桌面所在的那种屏上，车机上是它自己的显示器
        Display d = desktopDisplay();
        if (d != null && apps.resumeApp(d, prefs.getFloat(PREF_MOUSE_SPEED, 1f), prefs.getBoolean(PREF_ALWAYS_CURSOR, false))) {
            showSetup("正在回到 " + apps.label(), "桌面窗口关了，外接屏上还有这个应用。", true, null, null);
            return;
        }
        if (apps.hasInjector()) apps.stop();
        showChooser();
        dashboardTried = Display.INVALID_DISPLAY;
        ensureDashboard();
    }

    /** 明确结束：应用移回手机，外接屏恢复镜像，键鼠、声音路由和界面大小恢复。Termux 桌面继续运行。 */
    private void confirmEndXDesk() {
        ui().confirm("结束 XDesk？", "外接屏上的应用回到手机（不关闭），外接屏恢复镜像，键鼠和声音恢复正常。Termux 桌面继续运行，只是没有窗口。", "结束", new Runnable() {
            @Override public void run() {
                releaseLatches();
                client = null;
                DesktopService.endAll(XDeskActivity.this);
                finishAndRemoveTask();
            }
        });
    }

    // --- 外接屏仪表盘 ---

    /** XDesk 开着又有外接屏时，它就在最底下。外接屏上已有应用或桌面时不放，否则它会盖住它们。车机上 CarActivity 就是仪表盘。 */
    private void ensureDashboard() {
        Display d = externalDisplay();
        if (d == null || TargetDisplay.isCar(d) || apps.hasAppOnDisplay() || session.isActive()) return;
        if (dashboardTried == d.getDisplayId()) return;
        startDashboard(d);
    }

    private void startDashboard(Display d) {
        if (DashboardActivity.isAlive()) return;
        dashboardTried = d.getDisplayId();
        try {
            // 这个前台服务让从最近任务划掉时收得到 onTaskRemoved，否则仪表盘会一直留在外接屏上
            if (!DesktopService.isRunning()) startForegroundService(new Intent(this, DesktopService.class));
            Intent i = new Intent(this, DashboardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(d.getDisplayId());
            startActivity(i, o.toBundle());
            refocusSelf();
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot show the dashboard", e);
        }
    }

    // --- 安卓应用 ---

    /** 权限和注入服务都就绪才启动。缺什么说什么，弄好了回来会接着走（onResume、onInjectorSourceChanged）。 */
    private void startApp(final AppCatalog.App app) {
        if (apps.isStopping()) {
            showSetup("正在启动 " + app.label, "等上一个注入服务结束。", true, null, null);
            ui.postDelayed(new Runnable() {
                @Override public void run() { startApp(app); }
            }, STOPPING_RETRY_MS);
            return;
        }
        Display d = externalDisplay();
        if (d == null) {
            pendingApp = null;
            needDisplayNotice();
            return;
        }
        pendingApp = app;
        if (!apps.injectorReady()) {
            if (!shizukuScreens) showActivation(app);
            else showShizukuSetup();
        } else if (!Settings.canDrawOverlays(this)) {
            closeActivation();
            showSetup("需要悬浮窗权限", "安卓自己的光标只留在手机屏上，\nXDesk 要在外接屏上画一个光标。\n\n允许后回到这里，会自动继续。", false, "打开设置", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                }
            });
        } else {
            closeActivation();
            pendingApp = null;
            shizukuScreens = false;
            rememberRecent(ChooserView.recentKey(app));
            showSetup("正在启动 " + app.label, "正在连接注入服务。", true, null, null);
            leaveDesktop();
            apps.start(app, d, prefs.getFloat(PREF_MOUSE_SPEED, 1f), prefs.getBoolean(PREF_ALWAYS_CURSOR, false));
        }
    }

    /** 启动器顶部的提示：安卓应用能不能用，不能用就引导去激活。和外接屏无关，配对只要手机。 */
    private void updateActivationPrompt() {
        if (chooser == null) return;
        ShellActivation shell = apps.shell();
        String text;
        if (apps.injectorReady()) text = null;
        else if (!shell.isPaired()) text = "外屏运行安卓应用要先激活一次 · 去激活";
        else if (shell.isStarting()) text = "正在启动注入服务…";
        else text = "注入服务没有运行 · 去启动";
        chooser.setActivation(text, () -> {
            retryRequested = true;
            activationFlow = true;
            showActivation(null);
        });
    }

    /**
     * 没有 Shizuku 也行：XDesk 自己通过无线调试启动注入服务。
     * @param app 激活完要启动的应用，从启动器的提示进来时是 null
     */
    private void showActivation(AppCatalog.App app) {
        final ShellActivation shell = apps.shell();
        if (!shell.isPaired() || shell.isPairing()) {
            if (activation == null || !activation.isShowing()) openActivation();
            return;
        }
        closeActivation();
        // 用户点了才再试，上次失败了也一样。状态变化引起的刷新不重试，否则失败会接着失败地循环。
        // 用户在设置里停了服务，选了安卓应用就是要它再启动
        if ((retryRequested || shell.isStoppedByUser()) && !shell.isRunning() && !shell.isStarting()) shell.startByUser();
        retryRequested = false;
        if (shell.isStarting()) {
            showSetup(app != null ? "正在启动 " + app.label : "正在启动注入服务", "正在启动注入服务。", true, null, null);
            return;
        }
        showSetup("还差一步", shell.problem() != null ? shell.problem() : "注入服务没有运行。", false, "打开无线调试设置", new View.OnClickListener() {
            @Override public void onClick(View v) { openWirelessSettings(); }
        });
        addSetupChoice("重新配对", new View.OnClickListener() {
            @Override public void onClick(View v) { shell.forgetPairing(); }
        });
    }

    private void openActivation() {
        showSetup("激活注入服务", "", false, null, null);
        activation = new ActivationPage(this, ui(), apps.shell(), apps.shizukuInstalled(), new ActivationPage.Actions() {
            @Override public void openPairingSettings() { beginPairing(); }
            @Override public void useShizuku() {
                shizukuScreens = true;
                closeActivation();
                if (pendingApp != null) startApp(pendingApp);
            }
            @Override public void cancelled() {
                activation = null;
                backToChooser();
            }
        });
    }

    private void closeActivation() {
        if (activation != null) activation.close();
        activation = null;
    }

    /** 配对框开着时才有配对码，所以先把找端口的服务开起来，用户在系统设置里也能从通知输入。 */
    private void beginPairing() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pairingAfterPermission = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        startPairingService();
    }

    private void startPairingService() {
        try {
            startForegroundService(PairingService.startIntent(this));
        } catch (RuntimeException e) {
            Log.w(TAG, "pairing service", e);  // 页面里的输入框不靠它
        }
        openWirelessSettings();
    }

    private void openWirelessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "打不开开发者选项，到系统设置里找一下。", Toast.LENGTH_LONG).show();
        }
    }

    /** 选择界面上说一声还差什么，比如重启后无线调试关了。 */
    private void showShellProblem() {
        String problem = apps.shizukuState() == AppSession.SHIZUKU_READY ? null : apps.shell().problem();
        if (problem != null) chooser.setNotice(problem);
        else if (shownShellProblem != null) chooser.setNotice(null);
        shownShellProblem = problem;
        updateActivationPrompt();
    }

    /** 用户选了 Shizuku，或者系统太旧用不了无线调试。 */
    private void showShizukuSetup() {
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
        }
    }

    /** 把外接屏让给安卓应用。桌面窗口留在后面，桌面继续运行，切回来直接放到前面；这期间桌面没有声音。 */
    private void leaveDesktop() {
        if (!session.isActive()) return;
        // 锁定的修饰键是按在桌面上的，换到应用之前在桌面上松开
        releaseLatches();
        ui.removeCallbacks(connectTimeout);
        session.abandon();
        updateCapture();
    }

    private void attachApp() {
        client = apps.input();
        showRunning();
        updateCapture();
        updateStatus();
        bumpIdle();
    }

    // --- Termux 桌面 ---

    /** XDesk 只当显示器：放好窗口就等桌面连上来，桌面怎么起由用户决定。 */
    private void attachDisplay(Display d) {
        // 锁定的修饰键是按在应用上的，换成桌面之前松开
        releaseLatches();
        apps.suspend();
        desktopOnCar = TargetDisplay.isCar(d);
        audioRoute.setCar(desktopOnCar);
        if (desktopOnCar) Log.i(TAG, "desktop on the car, display=" + d.getDisplayId() + " size=" + desktopSize(d));
        placeX11(d.getDisplayId());
        applySize(d);
        session.attach();
        apps.connectForAudio();
        client = session.input();
        updateCapture();
    }

    /** 接回已经在运行的桌面，不启动也不重启任何东西。 */
    private void reattach() {
        Display d = externalDisplay();
        if (d == null) return;
        attachDisplay(d);
        desktopName = runningSession;
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
        desktopName = name;
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
        // xdesk switch 不断开 X，不会再有“连上了”的通知
        if (session.isConnected()) {
            showRunning();
            updateStatus();
            return;
        }
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (r == REQ_NOTIFICATIONS && pairingAfterPermission) {
            pairingAfterPermission = false;
            startPairingService();
            return;
        }
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
        if (session.isConnected()) {
            showRunning();
            updateStatus();
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
            setStatus(Style.DANGER, (displayId == CarActivity.displayId() ? "无法把桌面放到车机: " : "无法把桌面放到外屏: ") + e.getMessage());
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
                // 焦点回到了手机屏，下一个实体按键要先把它要回去
                if (apps.isActive()) apps.phoneTouched();
            }
        }, REFOCUS_DELAY_MS);
    }

    /** 只结束桌面。XDesk 留着，外接屏上的安卓应用也不受影响。 */
    private void stopDesktop() {
        if (session.isActive()) desktopGone();
        session.stop();
        // 切到安卓应用时桌面窗口留在了后面
        MainActivity m = MainActivity.getInstance("");
        if (m != null) m.finishAndRemoveTask();
        runningSession = null;
        desktopName = null;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (chooser.getVisibility() == View.VISIBLE) refreshTermux();
            }
        }, STOP_REFRESH_MS);
        if (chooser.getVisibility() == View.VISIBLE) refreshChooser();
    }

    private void confirmStop() {
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            ui().confirm("结束桌面", "XDesk 没有运行 Termux 命令的权限。请在 Termux 里运行：\n\nxdesk stop", "知道了", null);
            return;
        }
        ui().confirm("结束桌面？", "桌面会退出，未保存的内容会丢失。", "结束", new Runnable() {
            @Override public void run() { stopDesktop(); }
        });
    }
}
