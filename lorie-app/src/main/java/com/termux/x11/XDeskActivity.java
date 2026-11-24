package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
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
import com.termux.x11.mac.AutoQuality;
import com.termux.x11.mac.HostSettings;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacDiscovery;
import com.termux.x11.mac.MacStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 打开后先选外接屏上运行什么（Termux 桌面或安卓应用），手机屏做触摸板和键盘。流程见 PLAN.md。 */
public class XDeskActivity extends Activity {
    private static final String TAG = "XDesk";
    private static final String PERM = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_RUN_COMMAND = 1, REQ_NOTIFICATIONS = 2, REQ_LIST_SESSIONS = 3, REQ_PERM_CENTER = 4;
    /** What waits for the Termux command permission allowed on the app's details page (none, a desktop, the list, a stop). */
    private static final int AWAIT_NONE = 0, AWAIT_DESKTOP = 1, AWAIT_LIST = 2, AWAIT_STOP = 3;
    private int awaitingRunCommand = AWAIT_NONE;
    private static final String PKG_TERMUX = "com.termux";
    private static final String SCRIPT = "/data/data/com.termux/files/usr/bin/xdesk";
    /**
     * One read of Termux: xdesk app state, one line of JSON (CLI.md §2). A script that does not know it exits
     * non-zero, and the same command falls back to the three it does know, whose text this app still parses.
     */
    private static final String TERMUX_STATE_COMMAND = DesktopSession.nonceEnv() + SCRIPT + " app state 2>/dev/null || { "
        + SCRIPT + " list; echo ---; " + SCRIPT + " status; echo ---; " + SCRIPT + " envs; }";
    private static final String SHIZUKU_HOME = "https://shizuku.rikka.app/";
    private static final int NO_DISPLAY = R.string.xd_no_display;
    private static final String TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/";

    private static final String PREF_SCROLL_SPEED = "pad_scroll_speed", PREF_COVER = "cover_external";
    /** Input settings of INPUT.md §12 and §5: one for every device. */
    private static final String PREF_NATURAL_SCROLL = "natural_scroll", PREF_TRANSLATE_MODIFIERS = "translate_modifiers",
        PREF_KEYS_VIA_IME = "keys_via_ime", PREF_IME_CORRECTIONS = "ime_corrections";
    private static final String PREF_OUTPUT = "audio_output", PREF_WIDTH = "desktop_width", PREF_SPEED = "pad_speed",
        // Seconds without a touch before the phone dims; 0 is off. The old boolean key is read once.
        PREF_DIM = "dim_after_s", PREF_DIM_OLD = "dim_idle",
        PREF_DIAG = "show_diag", PREF_ORIENTATION = "orientation", PREF_RECENTS = "recents",
        PREF_SESSIONS = "sessions", PREF_MOUSE_SPEED = "mouse_speed", PREF_DOCK = "dock", PREF_EXTERNAL_DOCK = "external_dock", PREF_ALWAYS_CURSOR = "always_cursor",
        PREF_FULL_PAD = "pad_fullscreen", PREF_NOTIFICATIONS_ASKED = "notifications_asked", PREF_DESKTOP_DENSITY = "desktop_density",
        PREF_NOTIFY_ACCESS_ASKED = "notify_access_asked", PREF_NOTIFY_ACCESS_OFFERED = "notify_access_offered",
        /** The first-start permission checklist: shown once; done when finished or skipped. */
        PREF_PERM_ONBOARD_SEEN = "perm_onboard_seen", PREF_PERM_ONBOARD_DONE = "perm_onboard_done";
    /** The phone's interface (Settings › General): the console (touchpad first) or the Launchpad (app grid first). */
    private static final String PREF_PHONE_UI = "phone_ui";
    private static final String UI_CONSOLE = "console", UI_LAUNCHPAD = "launchpad";
    /** Interface size of the external display under a Termux desktop: small enough for the input method's candidates. */
    private static final int DESKTOP_DENSITY = 168;
    /** IME this tall (part of the window) is a soft keyboard; less is the candidate bar of a physical keyboard. */
    private static final float SOFT_KEYBOARD_PART = 0.25f;
    private static final int MAX_RECENTS = 6;
    private static final long TERMUX_REPLY_TIMEOUT_MS = 8000;
    /** 切换时等 Termux 说哪个环境在运行，等不到就按上次知道的。 */
    private static final long TERMUX_SWITCH_WAIT_MS = 3000;
    /** xdesk stop 要等桌面退出，过一会再看状态。 */
    /**
     * The desktop's resolution (17 November): auto (native up to AUTO_MAX_HEIGHT, a whole fraction of a taller panel,
     * so a 4K screen gets 1920x1080 at exactly 2x: sharp, and a quarter of the pixels to draw), native, 1080p, 720p;
     * by height, the width following the screen's shape. Before it was a width, PREF_WIDTH, read once to carry it over.
     */
    private static final String PREF_RESOLUTION = "desktop_resolution";
    private static final int RES_AUTO = 0, RES_NATIVE = 1, RES_1080 = 2, RES_720 = 3, AUTO_MAX_HEIGHT = 1600;
    private static final int[] RES_LABELS = {R.string.xd_res_auto, R.string.xd_res_native, R.string.xd_res_1080, R.string.xd_res_720};
    private static final int[] ORIENTATION_LABELS = {R.string.xd_orientation_system, R.string.xd_orientation_portrait, R.string.xd_orientation_landscape};
    /** 存的值，顺序同上。没选过就跟随系统；以前明确选过的不变。 */
    private static final int[] ORIENTATION_VALUES = {2, 1, 0};
    private static final int ORIENTATION_DEFAULT = 2;
    /** 自动调暗：关、30 秒、1 分钟、5 分钟、10 分钟。顺序同 DIM_SECONDS。 */
    private static final int[] DIM_LABELS = {R.string.xd_off, R.string.xd_dim_30s, R.string.xd_dim_1m,
        R.string.xd_dim_5m, R.string.xd_dim_10m};
    private static final int[] DIM_SECONDS = {0, 30, 60, 300, 600};
    private static final int DIM_DEFAULT_SECONDS = 60;
    private static final int SIDE_WIDTH = 96, SIDE_WIDTH_TOUCH = 56;
    private static final int[] ORIENTATIONS = {ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_FULL_USER};

    private static final long CONNECT_TIMEOUT_MS = 40000;
    /** 注入服务还在结束时选了应用：等它结束再启动，否则新的会被一起结束。 */
    private static final long STOPPING_RETRY_MS = 200;
    static final int DENSITY_MIN = 120, DENSITY_MAX = 220;
    private static final long RECAPTURE_DELAY_MS = 500;
    private static final long REFOCUS_DELAY_MS = 800;
    private static final int SCAN_A = 30, SCAN_X = 45, SCAN_C = 46, SCAN_V = 47, SCAN_Z = 44;
    private static final float DIM_BRIGHTNESS = 0.01f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private InputManager inputs;
    private InputMethodManager ime;
    private SharedPreferences prefs;
    /** The prefs file held a key from before 0.7.0 when this window opened (read before the first 0.7.0 write). */
    private boolean oldPrefsAtStart;

    private FrameLayout screens;
    private ExternalCover cover;
    private boolean forwardingBeforeCover;
    /** The Termux desktop has the keyboard: focus and the input method are on the external display, see setDesktopTaken. */
    private boolean desktopTaken, takenBeforeCover;
    /** 换到安卓应用时桌面是接管着的：切回桌面、窗口接上之后再接管。 */
    private boolean takeDesktopWhenBack;
    private int retakeTries;
    private static final int RETAKE_DELAY_MS = 500, RETAKE_TRIES = 6;
    private final Runnable retakeDesktop = new Runnable() {
        @Override public void run() {
            if (!session.isConnected() || apps.isActive() || desktopTaken) return;
            if (apps.hasInjector() && desktopDisplay() != null) setDesktopTaken(true);
            else if (--retakeTries > 0) ui.postDelayed(this, RETAKE_DELAY_MS);
        }
    };
    private View keyboardBtn, comboRow;
    private View runningScreen, setupScreen, topBar, keysPanel, leftButton, rightButton, leftColumn, rightColumn;
    /** KeysPanel.dc.html's line under the keys, shown while a modifier is latched. */
    private TextView keysNote;
    private Style.IconButton keysHandle;
    /** The two corners the full-screen touch area swaps by content kind; either may be null. */
    private Style.IconButton fullscreenBackBtn, fullscreenKeyboardBtn;
    private DirectTouchView directTouch;
    private boolean keysExpanded, keyboardOpen, phonePassive;
    /** 触控区铺满整个手机屏，系统栏隐藏。 */
    private boolean padFullscreen;
    /** The phone is a gamepad (GAMEPAD.md): gamepadView covers everything. */
    private boolean gamepadShown;
    private GamepadView gamepadView;
    /** 这个显示器已经放过仪表盘了，放不上去也别一直试。 */
    private int dashboardTried = Display.INVALID_DISPLAY;
    /** 认得的外接屏。拔掉时系统已经查不到它是什么屏了，只能靠记下的 id 认出来。 */
    private final java.util.Set<Integer> realDisplays = new java.util.HashSet<Integer>();
    /** 上次知道的车机显示器，没有是 INVALID_DISPLAY。 */
    private int carSeen = Display.INVALID_DISPLAY;
    /** 桌面放在了车机上。会话进行中不因为另一种屏接上就挪走。会话在进程里，界面重建后也要知道，所以是 static。 */
    private static boolean desktopOnCar;
    private ChooserView chooser;
    /** The Launchpad mode's grid (Settings › General › Phone interface); hidden in the console mode. */
    private LaunchpadView launchpad;
    /** In the Launchpad mode the phone's page is the grid: something opened on the screen does not take it to the pad. */
    private boolean launchpadPage;
    /** The line above the launchers (a warning, no display): the grid is rebuilt on a turn and says it again. */
    private String launcherNotice;
    /** What tapping the launcher's line does (the no-display line opens the receiver picker). */
    private Runnable launcherNoticeAction;
    /** 启动器上的提示现在是 NO_DISPLAY。 */
    private boolean displayNoticeShown;
    private AppSession apps;
    private AppCatalog catalog;
    private AudioRoute audioRoute;
    private WirelessDisplay wireless;
    private WebServer web;
    /** The code a browser should type, shown on the phone while it pairs. */
    private android.app.Dialog webCodeDialog;
    /** The pairing in progress, so Settings › Web display can show it too (SetWeb.dc.html's green card). */
    private String webPairingBrowser, webPairingCode;
    /** The open Settings › Web display page, rebuilt when a pairing starts or ends. */
    private android.app.Dialog webPage;
    private DockBar runningDock;
    private ExternalDock externalDock;
    /** The notification centre's windows on the external screen (NOTIFY.md): the bell, the banners, the panel. */
    private Notify notify;
    /** The Mac menu's windows (MACMENU.md): the ≡ button and the bar, over a single Mac window or app. */
    private MacMenus menus;
    /** What the switchboard (Switchboard: the phone's list, the external Launchpad, the Docks) asks of this window. */
    private final Switchboard.Host boardHost = new Switchboard.Host() {
        @Override public List<String> favourites() { return savedLines(PREF_DOCK); }
        @Override public String current() { return currentKey(); }
        @Override public String previous() { return previousKey(); }
        @Override public List<String> running() {
            ArrayList<String> keys = new ArrayList<>();
            String current = currentKey(), previous = previousKey();
            if (current != null) keys.add(current);
            if (previous != null && ChooserView.isAppKey(previous)) keys.add(previous);
            // Only what is open: a Mac that is not connected still lists its desktop (that entry is how to connect it)
            for (ContentSource s : Contents.sources()) for (ContentSource.Entry e : s.windows()) if (e.running) keys.add(e.key);
            return keys;
        }
        @Override public boolean onCar() { return XDeskActivity.this.onCar(); }
        @Override public void open(String key) {
            if (ExternalLauncher.SWITCHER.equals(key)) showSwitcher(false);
            else chooseKey(key);
        }
        @Override public boolean card(String key) { return showItemCard(key); }
        @Override public Context phone() { return isFinishing() || isDestroyed() ? null : XDeskActivity.this; }
        @Override public boolean stopAsks() { return XDeskActivity.this.stopAsks(); }
        @Override public void termuxHint() { chooserHost.onTermuxHint(); }
        @Override public void updateCommand() { updateXdeskCommand(); }
        @Override public void attachX() { chooserHost.onAttachChosen(); }
    };
    private Dialog outputDialog;
    /** 设置首页开着时的声音输出那一行，选了输出就跟着变。 */
    private TextView settingsOutputValue;
    /** 这次接上的桌面环境名，只当显示器时不知道。 */
    private String desktopName;
    private int lastOrientation;
    private TextView setupTitle, setupMessage, setupAction, setupSecond, setupBack;
    /** The key over the title when the setup page asks for a permission (PermitCard's look). */
    private ImageView setupKey;
    /** The XDesk wordmark over the page; the mockups' waiting and failure pages do not have it. */
    private TextView setupBrand;
    /** A big round icon over the title (the failure page's red link-off, ExtCover/ConnectFail). */
    private FrameLayout setupIconBox;
    private ImageView setupIcon;
    /** The waiting page's command and the line under it, and the failure page's second line (Waiting, ConnectFail). */
    private TextView setupCode, setupNote2;
    private ProgressBar setupProgress;
    /** The waiting page's spinner above the title (Waiting.dc.html), apart from the pages' progress line. */
    private ProgressBar setupSpinTop;
    private TextView statusText, diagText;
    /** Holds the status dot and text: shown only for warnings and errors, the buttons already say the rest. */
    private View statusRow;
    private Style.IconButton audioBtn, touchBtn, forwardBtn, gamepadBtn, fullscreenBtn, launcherBtn, dimBtn, settingsBtn, backBtn;
    private View statusDot;
    private TouchpadView touchpad;
    private KeyInputView keyView;

    private volatile InputSink client;
    private DesktopSession session;
    private boolean captured, dimmed, resumed;
    /** The touch that brought the screen back from dim: its whole gesture is swallowed, not just the down. */
    private boolean dimTouchSwallowed;
    /** 桌面窗口离开了外屏，还没放回去。App 在后台时系统可能拦下放置，回到前台再补。 */
    private boolean needsPlacement;
    /** 选了这个安卓应用，还差权限或 Shizuku。回到前台或 Shizuku 有变化时接着走。 */
    private AppCatalog.App pendingApp;
    /** 正开着的激活页。 */
    private ActivationPage activation;
    /** The “Use Shizuku” page while it is open (Shizuku.dc.html). */
    private ShizukuPage shizukuPage;
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
    /** The xdesk command's own version (CLI.md §1); null when the script is too old to report one. */
    private String cliVersion;
    /** What the Termux section says of the command's version and of X running code from before the update. */
    private String termuxVersionHint;
    private final Runnable termuxNoReply = new Runnable() {
        @Override public void run() {
            setChooserTermux(savedSessions(), null, runningSession, false, getString(R.string.xd_termux_no_reply), null);
        }
    };
    private int keyEvents, mouseEvents;
    private float mouseRestX, mouseRestY;
    /** PREF_MOUSE_SPEED, for the captured mouse. */
    private float mouseSpeed = 1f;
    /** PREF_SCROLL_SPEED for the wheel of a mouse on the phone, as for the touchpad (INPUT.md §12). */
    private float wheelSpeed = 1f;
    private final List<Latch> latches = new ArrayList<Latch>();

    private static final class Latch {
        final int scan;
        final TextView button;
        boolean on;
        Latch(int scan, TextView button) { this.scan = scan; this.button = button; }
    }

    // --- 输入路由（INPUT.md §9）：每个设备经 Router 到当时的 client ---

    private final Router router = new Router(new Router.Host() {
        @Override public InputSink current() { return client; }
        @Override public void hostAction(InputSink target, int action, int arg) { inputAction(target, action, arg); }
        @Override public void caretMayMove() {
            KeyInputView v = keyView;
            if (v != null) v.bridge().forgetSoon();
        }
        @Override public InputSink content() {
            InputSink c = client;
            if (c == SwitcherOverlay.INPUT) c = beforeSwitcher;
            return c == null && apps.isActive() ? apps.input() : c;
        }
        @Override public boolean tabletChosen(InputSink content) { return content == apps.input() && apps.isTouchMode(); }
    });
    private final Router.Device padIn = router.device(Router.PHONE_PAD), keysIn = router.device(Router.PHONE_KEYS),
        mouseIn = router.device(Router.PHONE_MOUSE), remoteIn = router.device(Router.REMOTE);
    /**
     * The phone's mouse and touchpad, and a receiver's pointer over X or a Mac, pass through this on their way to the
     * Router: over X or a Mac it opens the external Dock when the content's pointer is pushed against the bottom, and
     * the Dock has the pointer while open.
     */
    private final DockPointer dockPointer = new DockPointer(new DockPointer.Host() {
        @Override public InputSink target() { return client; }
        @Override public ExternalDock dock() { return externalDock; }
        @Override public Notify notifyCenter() { return notify; }
        @Override public MacMenus menus() { return menus; }
    }, mouseIn, padIn, remoteIn);

    private final io.github.n2ray.xdesk.input.GestureOutput output = new io.github.n2ray.xdesk.input.GestureOutput() {
        @Override public void move(int dx, int dy) {
            wireless.phonePointerMoved();
            dockPointer.move(padIn, dx, dy);
        }
        @Override public void button(int b, boolean down) { dockPointer.button(padIn, b, down); }
        @Override public void click(int b) { dockPointer.click(padIn, b); }
        @Override public void scroll(int phase, float dx, float dy) {
            // While the Dock or the notification centre has the pointer its wheel is not the content's (the centre scrolls)
            if (dockPointer.scroll(dy, dx)) return;
            padIn.scrollFine(dy, dx, phase);
        }
        @Override public void action(int a, int arg) { padIn.action(a, arg); }
    };

    /** 键鼠归属（INPUT.md §7）：两种会话一个开关、一个 Ctrl+Alt+M。 */
    private final Ownership ownership = new Ownership(new Ownership.Side() {
        @Override public boolean active() { return apps.isActive(); }
        @Override public boolean forwarded() { return apps.isForwarding(); }
        @Override public void forward(boolean on) {
            apps.setForwarding(on);
            if (!on) closeDockForPhone();
        }
    }, new Ownership.Side() {
        // The X desktop, its Linux windows and a Mac: the phone's own window passes keys and mouse on (INPUT.md §5.1,
        // the keys through the phone's input method). With that switched off, the X desktop's old takeover (focus
        // and the input method on the external display's desktop window) is the way, as in 0.5.1
        @Override public boolean active() { return !apps.isActive() && (session.isActive() || contentKey != null || SplitStageActivity.isActive()); }
        @Override public boolean forwarded() { return oldTakeover() ? desktopTaken : phoneKeys; }
        @Override public void forward(boolean on) {
            if (!on) closeDockForPhone();
            if (oldTakeover()) {
                setDesktopTaken(on);
                return;
            }
            phoneKeys = on;
            if (!on) {
                keysIn.cancel();
                mouseIn.cancel();
                heldModifiers.clear();
                if (keyView != null) keyView.clearMouse();
            }
            updateCapture();
            updateStatus();
        }
    });

    /** The phone's window passes its physical keyboard and mouse to the target (not after Ctrl+Alt+M gave them back). */
    private boolean phoneKeys = true;

    /** INPUT.md §5.1 switched off: the X desktop takes keys as scan codes and is taken over the 0.5.1 way. */
    private boolean oldTakeover() {
        return session.isActive() && !prefs.getBoolean(PREF_KEYS_VIA_IME, true);
    }

    /** Physical keys and the mouse in the phone's window go on to the target. The old way always passed them. */
    private boolean phoneForwards() {
        return phoneKeys || oldTakeover();
    }

    /** The keys and mouse went to the phone: the external Dock had only that pointer to work it. */
    private void closeDockForPhone() {
        if (externalDock != null) externalDock.closeForPhone();
        if (notify != null) notify.closeForPhone();
        if (menus != null) menus.closeForPhone();
    }

    /** Whether the app session had the keys and mouse at the last state: there the injector's evdev reader hears Ctrl+Alt+M. */
    private boolean appForwarded;
    /** Game passthrough at the last app state (GAMING.md), to say its coming and going once. */
    private boolean appGame;

    /** 应用切换（三指横滑，按住不放）：Dock 里的前一个、后一个，抬起手指时才换过去。 */
    private final ArrayList<String> switchKeys = new ArrayList<String>();
    private int switchIndex;

    /** XDesk 自己做的动作：安卓应用、单个窗口和切换器的（INPUT.md §3.4）。 */
    private void inputAction(final InputSink target, final int action, final int arg) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                @Override public void run() { inputAction(target, action, arg); }
            });
            return;
        }
        io.github.n2ray.xdesk.input.Caps caps = target != null ? target.caps() : null;
        boolean window = caps != null && caps.scope == io.github.n2ray.xdesk.input.Caps.WINDOW;
        switch (action) {
            case io.github.n2ray.xdesk.input.Input.BACK:
                if (target == SwitcherOverlay.INPUT) target.androidButton(1);
                // A window (a Mac window): its own Back (⌘[ for a Mac), never the Android app behind it
                else if (window && target != null) target.androidButton(1);
                else if (apps.isActive() && target == apps.input()) apps.sendBack();
                break;
            case io.github.n2ray.xdesk.input.Input.HOME:
                if (target == SwitcherOverlay.INPUT) target.androidButton(2);
                else if (window) showSwitcher(true);
                else goHomeOnDisplay();
                break;
            case io.github.n2ray.xdesk.input.Input.OVERVIEW:
                // A Mac window: XDesk's switcher for this Mac only (§3.4), as its Home
                if (window && target instanceof MacInput) target.androidButton(3);
                else showSwitcher(true);
                break;
            case io.github.n2ray.xdesk.input.Input.SWITCHER:
                showSwitcher(false);
                break;
            case io.github.n2ray.xdesk.input.Input.SWITCH_APP:
                // A Mac window: this Mac's own windows (§3.4), not the Dock's list
                if (window && target instanceof MacInput) ((MacInput) target).stepWindow(arg);
                else switchAppStep(arg);
                break;
            default:
                // 换屏（只有一块屏时没有）、其他目标不认的动作：丢掉，不报错（§6）
                break;
        }
    }

    private void switchAppStep(int arg) {
        if (arg == 0) {
            if (switchIndex != 0 && switchIndex < switchKeys.size()) chooseKey(switchKeys.get(switchIndex));
            switchKeys.clear();
            switchIndex = 0;
            return;
        }
        if (switchKeys.isEmpty()) {
            String current = currentKey(), previous = previousKey();
            if (current != null) switchKeys.add(current);
            if (previous != null && !switchKeys.contains(previous)) switchKeys.add(previous);
            for (String k : savedLines(PREF_DOCK)) if (!switchKeys.contains(k)) switchKeys.add(k);
            if (current == null) switchKeys.add(0, "");
            switchIndex = 0;
        }
        int n = switchKeys.size();
        if (n < 2) return;
        switchIndex = ((switchIndex + arg) % n + n) % n;
    }

    private final KeyInputView.Sink keySink = new KeyInputView.Sink() {
        @Override public Object target() { return client; }
        @Override public boolean physicalKey(KeyEvent e) { return XDeskActivity.this.physicalKey(e, true); }
        @Override public boolean latched() {
            for (int i = 0; i < latches.size(); i++) if (latches.get(i).on) return true;
            return false;
        }
        @Override public void text(String s) {
            Log.d(TAG, "IME text: " + s.length() + " chars, client " + sinkName(client) + ", bound " + sinkName(keysIn.boundTo()));
            if (client == null) return;
            keysIn.text(s);
            releaseLatches();
        }
        @Override public void tapKey(int scan) {
            if (client == null) return;
            keysIn.tapKey(scan);
            releaseLatches();
        }
        @Override public void mouseMove(float dx, float dy) {
            mouseEvents++;
            updateDiag();
            if (client == null) return;
            // The mouse speed setting is for every mouse (INPUT.md §12), not only the injector's
            mouseRestX += dx * mouseSpeed;
            mouseRestY += dy * mouseSpeed;
            int x = (int) mouseRestX, y = (int) mouseRestY;
            if (x != 0 || y != 0) {
                mouseRestX -= x;
                mouseRestY -= y;
                wireless.phonePointerMoved();  // as the touchpad: the X cursor shows again, the receiver's pointer goes
                dockPointer.move(mouseIn, x, y);
            }
        }
        @Override public void mouseButton(int b, boolean down) {
            mouseEvents++;
            updateDiag();
            dockPointer.button(mouseIn, b, down);
        }
        @Override public void captureChanged(boolean has) {
            captured = has;
            updateDiag();
            // 系统在转屏等时候会收回抓取；窗口还有焦点、鼠标还在就要回来，否则光标会跑到手机屏上
            if (!has) {
                // Releases of buttons held now will not reach this window: they go up where they went down (§2)
                if (keyView != null) keyView.releaseMouse();
                ui.removeCallbacks(recapture);
                ui.postDelayed(recapture, RECAPTURE_DELAY_MS);
            }
        }
        @Override public void mouseScroll(float v, float h) {
            if (client == null || (v == 0 && h == 0) || dockPointer.scroll(-v, h)) return;
            // A wheel turned up is a positive AXIS_VSCROLL. High resolution wheels give fractions: fine scrolling
            // keeps them, the Router makes whole steps for a target that takes only those
            mouseIn.scrollFine(-v * wheelSpeed, h * wheelSpeed, io.github.n2ray.xdesk.input.Input.WHEEL);
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {
            // 只管外接屏。车机的显示器要等 CarActivity 起来才认得出（carChanged），Android Auto 别的显示器都不管
            Display added = displays.getDisplay(id);
            if (!TargetDisplay.isReal(added)) return;
            realDisplays.add(id);
            // A screen to dim beside appeared, even before anything is opened on it.
            if (!dimmed) bumpIdle();
            // Android may squeeze it (a wrong EDID size): the injector shows it on its whole panel, if it is there
            apps.checkPanels();
            ui.removeCallbacks(displayGone);
            DesktopScale.askKindOnce(XDeskActivity.this, ui(), added, XDeskActivity.this::screenSizingChanged);
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
                updateCapture();  // the home's Launchpad, if a mouse is there
                return;
            }
            // 可能换了显示器：按新尺寸算分辨率，再把桌面放回去
            desktopOnCar = false;
            // A Linux program shown as the content stayed full screen in X: it is on this display now
            if (contentKey != null && contentKey.startsWith(LinuxSource.PREFIX)) contentDisplay = id;
            applySize(added);
            placeX11(id);
            needsPlacement = !resumed;
            updateStatus();
            // The home over an apps-only X with nothing open was taken down with the display
            ui.removeCallbacks(homeAgain);
            ui.postDelayed(homeAgain, HOME_AGAIN_MS);
        }
        @Override public void onDisplayRemoved(int id) {
            MacLogin.displayGone(id);
            SwitcherOverlay.hide(switcherHost);
            if (contentKey != null && id == contentDisplay && !contentKey.startsWith(LinuxSource.PREFIX)) {
                // DISPLAY.md §5.4: a Mac stops streaming. A Linux window is left as it is, full screen in X, which
                // goes back onto the display when it returns (onDisplayAdded): that content does not end
                String gone = contentKey;
                displayGoing = true;  // nothing to go back to on a display that is gone
                contentsHost.contentEnded(gone);
                displayGoing = false;
                ContentSource s = Contents.sourceOf(gone);
                if (s != null) s.hidden(gone);
            }
            // Its windows went with the display
            updateExternalDock();
            if (id == carSeen) {
                carChanged();
                bumpIdle();
                return;
            }
            if (!realDisplays.remove(id)) return;
            bumpIdle();
            removeCover();
            // 不收的话系统会把它挪到手机屏上
            DashboardActivity.closeIfAlive();
            dashboardTried = Display.INVALID_DISPLAY;
            // DISPLAY.md §5.4: 插头松了几秒内又接上不算断开，告诉用户和收尾的事等 displayGone
            ui.removeCallbacks(displayGone);
            ui.postDelayed(displayGone, DISPLAY_GONE_MS);
            if (apps.isActive()) {
                apps.displayRemoved(id);
                refocusSelf();
                return;
            }
            // 桌面在车机上时拔掉外接屏，桌面不受影响
            if (!session.isActive() || desktopOnCar) return;
            needsPlacement = true;
            // 系统会把桌面窗口挪到手机屏上盖住触摸板，把 App 拉回前面
            refocusSelf();
        }
        @Override public void onDisplayChanged(int id) {
            if (apps.isActive()) apps.displayChanged(id);
            // A resized display (a receiver moving to the cable, a window resized) relaunches the app on it, and Android
            // hands the focus to the phone's screen meanwhile, where this window takes none: keys then wait for a window
            // that never comes (an ANR). The focus goes back once the app is there again
            if (phonePassive && id != Display.DEFAULT_DISPLAY) {
                ui.removeCallbacks(refocusAfterResize);
                ui.postDelayed(refocusAfterResize, REFOCUS_AFTER_RESIZE_MS);
                ui.postDelayed(refocusAfterResize, REFOCUS_AFTER_RESIZE_MS * 4);
            }
            // The X desktop follows its display too (a receiver window resized, a tablet turned), once the size has
            // settled: DISPLAY.md §4.3. Same size again changes nothing (the preference stays equal).
            Display shown = desktopDisplay();
            if (session.isActive() && shown != null && shown.getDisplayId() == id) {
                ui.removeCallbacks(desktopFollowsDisplay);
                ui.postDelayed(desktopFollowsDisplay, RESIZE_SETTLE_MS);
            }
        }
    };

    /** DISPLAY.md §5.4: a display removed is taken as gone once no display has come back for this long. */
    private static final long DISPLAY_GONE_MS = 3000;
    private final Runnable displayGone = () -> {
        if (!session.isActive() && !apps.isActive()) DesktopService.stopIfIdle(XDeskActivity.this);
        if (apps.isActive()) return;
        if (!session.isActive() || desktopOnCar) {
            updateChooserDisplay();
            return;
        }
        setStatus(Style.WARN, getString(R.string.xd_display_gone_desktop));
    };

    /** DISPLAY.md §4.3: a size is committed once it has not changed for this long. */
    private static final long RESIZE_SETTLE_MS = 300;
    private final Runnable desktopFollowsDisplay = () -> {
        Display d = desktopDisplay();
        if (session.isActive() && d != null) applySize(d);
    };

    private final DesktopSession.Listener sessionListener = new DesktopSession.Listener() {
        @Override public void onSessionChanged(boolean c) {
            if (!c && contentKey != null && contentKey.startsWith("l:")) contentsHost.contentEnded(contentKey);
            // 桌面窗口没了，焦点回不去它那里
            if (!c) setDesktopTaken(false);
            if (c) {
                ui.removeCallbacks(connectTimeout);
                checkServerBuild();
                adoptLaunched();
                syncTermuxLater();
                showOpened();
                desktopConnected();
                DesktopScale.connected();
                if (takeDesktopWhenBack) {
                    takeDesktopWhenBack = false;
                    retakeTries = RETAKE_TRIES;
                    ui.removeCallbacks(retakeDesktop);
                    ui.postDelayed(retakeDesktop, RETAKE_DELAY_MS);
                }
                Display shown = desktopDisplay();
                if (shown != null && !TargetDisplay.isCar(shown)) apps.setDesktopDensity(shown, prefs.getInt(PREF_DESKTOP_DENSITY, DESKTOP_DENSITY));
                bumpIdle();
                // X 没有画面变化就不刷新，窗口刚接上时会一直黑屏：动一下光标触发重绘
                session.input().move(1, 0);
                session.input().move(-1, 0);
            }
            // 车机断开时系统可能直接关掉桌面窗口：X 还在，等车机回来
            if (!c && desktopOnCar && TargetDisplay.car(displays) == null) {
                showSetup(getString(R.string.xd_car_gone_title), getString(R.string.xd_car_gone), false, getString(R.string.xd_back_to_launcher), new View.OnClickListener() {
                    @Override public void onClick(View v) { backToChooser(); }
                });
            } else if (!c) {
                showWaiting();
            }
            updateStatus();
        }
        @Override public void onDesktopStopped(boolean byUser) {
            // The built-in Linux stopped by the user (its page, the device list, settings) is no crash either
            if (!byUser && BuiltinLinux.get(XDeskActivity.this).stoppedByUserRecently()) byUser = true;
            // 不自动重启：启动和结束桌面都由用户决定
            runningSession = null;
            desktopName = null;
            launchedSession = null;
            Devices.get(XDeskActivity.this).setLinuxRunning(null);
            Devices.get(XDeskActivity.this).setXIme(false);
            desktopGone();
            // One card says it (it was a toast and a fixed line, the same thing twice)
            setLauncherNotice(null);  // every stop says so, also the same words as last time
            setLauncherNotice(getString(byUser ? R.string.xd_desktop_ended_notice : R.string.xd_desktop_crashed_notice));
        }
        @Override public void onDesktopStopFinished() {
            chooser.setStopping(null);
            if (restartOldServer) {
                // The old X server is gone: the same desktop again, on this build
                restartOldServer = false;
                launchDesktop(restartName, false);
                return;
            }
            if (launcherShown()) refreshTermux();
            else ui.post(syncTermux);  // the device tabs and the home follow it too
        }
        @Override public void onSessionError(String message) {
            showSetup(getString(R.string.xd_termux_unreachable), message, false, getString(R.string.xd_back_to_launcher), new View.OnClickListener() {
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
            if (shizukuPage != null && shizukuPage.isShowing()) shizukuPage.refresh();
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
            // The injector is a permission item too: a page waiting on it says so now
            refreshPermissionPages();
        }
        @Override public void onAppStarted() {
            attachApp();
            // Physical keys and mouse belong to the external screen (the phone's is worked by finger): every app starts
            // with them, whatever was switched for the one before (INPUT.md §12). Ctrl+Alt+M still gives them back.
            if (externalDisplay() != null && hasExternalInput()) apps.setForwarding(true);
            refocusSelf();
        }
        @Override public void onAppFailed(String message) {
            setTarget(null);
            router.cancelAll();
            showSetup(getString(R.string.xd_cannot_run_on_display), message, false, getString(R.string.xd_back_to_launcher), new View.OnClickListener() {
                @Override public void onClick(View v) { backToChooser(); }
            });
        }
        @Override public void onAppStateChanged() {
            // The injector hears Ctrl+Alt+M in an app session and gives the keys and mouse back itself: the Dock goes
            // with them. A Dock opened by the phone's touchpad while they are already the phone's is not one of these.
            boolean forwarding = apps.isForwarding();
            // Game passthrough came or went (GAMING.md): said once on the phone, where the way back is a touch
            boolean game = apps.isGameMode();
            if (game != appGame) Toast.makeText(XDeskActivity.this, game ? R.string.xd_game_on : R.string.xd_game_off, Toast.LENGTH_SHORT).show();
            appGame = game;
            if (appForwarded && !forwarding) closeDockForPhone();
            appForwarded = forwarding;
            updateStatus();
            refreshDocks();
        }
        @Override public void onAppGone() {
            if (apps.isActive() && !onCar() && appKey != null) {
                // MACMENU.md §8: the app on the external screen finished or was removed: the previous content,
                // or the Launchpad home. On the car there is no home: what was there today stays
                contentGone(appKey);
            } else {
                openChooserOverSession();
            }
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

    private final WirelessDisplay.Listener wirelessListener = new WirelessDisplay.Listener() {
        @Override public void onWirelessChanged() {
            audioRoute.setWireless(wireless.capturesSound());
            updateStatus();
            if (wireless.isActive()) stopAutoReceiver();
            else startAutoReceiver();
            // A wireless/web display (a Mac receiver among them) came or went: the idle timer follows.
            if (!dimmed) bumpIdle();
            if (launcherShown()) updateChooserDisplay();
        }
        @Override public void onWirelessDisplay(int id) {
            // 系统的回调可能在 XDesk 知道这块显示器之前就来了，那时被当成别人的虚拟显示器忽略掉
            if (!realDisplays.contains(id)) displayListener.onDisplayAdded(id);
        }
        @Override public void onWirelessPairing(String receiverName, boolean wrongBefore, WirelessDisplay.Pairing reply) {
            String note = getString(wrongBefore ? R.string.xd_wireless_code_wrong : R.string.xd_wireless_code_note, receiverName);
            // SetWirelessPair: one box per digit, Cancel and Pair
            ui().codeInput(getString(R.string.xd_wireless_pair_title, receiverName), note, 4,
                getString(R.string.xd_wireless_pair), code -> {
                    reply.answer(code);
                    return true;
                }, () -> reply.answer(null));
        }
        @Override public void onWirelessMessage(String message) {
            Toast.makeText(XDeskActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    /** 无线显示：局域网里找到的接收端、手动输入地址，投屏中可以停止。 */
    private void showWirelessPicker() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] d = new Dialog[1];
        final WirelessFinder finder = new WirelessFinder(this);
        if (wireless.isActive()) {
            // SetWireless: what it is connected to, then Stop in the same card; asked first, as stopping is
            LinearLayout connected = s.card(c);
            s.dotRow(connected, true, getString(R.string.xd_wireless_connected, wireless.receiverName()),
                connectedNote());
            s.stopRowIn(connected, getString(R.string.xd_wireless_stop, wireless.receiverName()), null, () ->
                s.confirm(getString(R.string.xd_wireless_stop_title), getString(R.string.xd_wireless_stop_message),
                    getString(R.string.xd_stop), SettingsUi.LEVEL_STOP, () -> {
                        d[0].dismiss();
                        wireless.forgetAutoReceiver();
                        wireless.stop();
                    }));
        }
        final LinearLayout found = s.section(c, getString(R.string.xd_wireless_nearby));
        // The searching line is a row of its own until the first receiver answers
        final LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        spinnerParams.rightMargin = dp(10);
        searchRow.addView(spinner, spinnerParams);
        final TextView searchText = Style.label(this, getString(R.string.xd_wireless_searching), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        searchRow.addView(searchText, new LinearLayout.LayoutParams(0, -2, 1));
        // The search stops after a while (it went on for as long as the page was open); this starts another
        final TextView searchAgain = Style.label(this, getString(R.string.xd_wireless_search_again), Style.ACCENT, Style.TEXT_SMALL + 1);
        searchAgain.setTypeface(Typeface.DEFAULT_BOLD);
        searchAgain.setPadding(dp(12), dp(6), dp(4), dp(6));
        searchAgain.setVisibility(View.GONE);
        searchRow.addView(searchAgain, new LinearLayout.LayoutParams(-2, -2));
        found.addView(searchRow);
        // The receivers go in here, above the address row, so "Enter address" stays last as in SetWireless
        final LinearLayout discovered = new LinearLayout(this);
        discovered.setOrientation(LinearLayout.VERTICAL);
        found.addView(discovered);
        final java.util.Set<String> shown = new java.util.HashSet<>();
        s.accentRow(found, getString(R.string.xd_wireless_address), getString(R.string.xd_wireless_address_note), () -> {
            d[0].dismiss();
            s.input(getString(R.string.xd_wireless_address), getString(R.string.xd_wireless_address_note), "192.168.1.20",
                wireless.lastAddress(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, getString(R.string.xd_wireless_connect),
                text -> {
                    String host = text;
                    int port = WirelessDisplay.PORT;
                    int colon = text.lastIndexOf(':');
                    // host:port, not an IPv6 address without a port
                    if (colon > 0 && text.indexOf(':') == colon) {
                        try {
                            port = Integer.parseInt(text.substring(colon + 1));
                            host = text.substring(0, colon);
                        } catch (NumberFormatException ignored) { }
                    }
                    startWireless(host, port, null);
                    return true;
                }, null);
        });
        TextView networkNote = Style.label(this, getString(R.string.xd_wireless_picker_note), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        networkNote.setPadding(dp(6), dp(6), dp(6), dp(2));
        c.addView(networkNote);
        LinearLayout receiverSide = s.section(c, getString(R.string.xd_wireless_receiver));
        s.switchRow(receiverSide, getString(R.string.xd_clipboard_sync), getString(R.string.xd_clipboard_sync_note), wireless.clipboardSyncOn(),
            on -> wireless.setClipboardSync(on));
        final java.util.List<ReceiverRow> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
        final boolean[] open = {true};
        final Runnable[] searchOver = new Runnable[1];
        d[0] = settingsPage(getString(R.string.xd_wireless), c, () -> {
            open[0] = false;
            if (searchOver[0] != null) searchRow.removeCallbacks(searchOver[0]);
            finder.stop();
            if (settingsRefresh != null) settingsRefresh.run();
        });
        // What the network announced may be long gone: a row shows only while its receiver answers
        final Runnable refreshSearching = () -> {
            boolean any = false;
            for (ReceiverRow row : rows) any |= row.view.getVisibility() == View.VISIBLE;
            searchRow.setVisibility(any ? View.GONE : View.VISIBLE);
        };
        new Thread(() -> {
            while (open[0]) {
                for (ReceiverRow row : rows) probeReceiver(row, refreshSearching);
                SystemClock.sleep(RECEIVER_PROBE_MS);
            }
        }, "xdesk-receiver-probe").start();
        final WirelessFinder.Listener onFound = r -> {
            // Anyone can announce an id: one that is already listed must not hide this address, or the other way round
            String key = r.id + "@" + r.host;
            if (!shown.add(key)) return;
            int at = discovered.getChildCount();
            s.linkRow(discovered, r.name, r.host, "", () -> {
                closeSettings();
                startWireless(r.host, r.port, r.name);
            });
            View view = discovered.getChildAt(at);
            view.setVisibility(View.GONE);
            ReceiverRow row = new ReceiverRow(r.host, r.port, view);
            rows.add(row);
            new Thread(() -> probeReceiver(row, refreshSearching), "xdesk-receiver-probe").start();
        };
        // Search for a while, then stop: receivers found stay listed (and probed); none found says so, with Search again
        searchOver[0] = () -> {
            if (!open[0]) return;
            finder.stop();
            spinner.setVisibility(View.GONE);
            searchText.setText(R.string.xd_wireless_none);
            searchAgain.setVisibility(View.VISIBLE);
        };
        final Runnable search = () -> {
            spinner.setVisibility(View.VISIBLE);
            searchText.setText(R.string.xd_wireless_searching);
            searchAgain.setVisibility(View.GONE);
            finder.start(onFound);
            searchRow.removeCallbacks(searchOver[0]);
            searchRow.postDelayed(searchOver[0], RECEIVER_SEARCH_MS);
        };
        searchAgain.setOnClickListener(v -> {
            Style.tick(v);
            search.run();
        });
        search.run();
    }

    /** The line under a connected receiver's name: where the keys go (SetWireless). */
    private String connectedNote() {
        return getString(R.string.xd_wireless_input_at, wireless.receiverName());
    }

    /** While XDesk is open and idle: the computer used last, when it shows up on the network. */
    private void startAutoReceiver() {
        final String id = wireless.autoReceiverId();
        if (!activityStarted || autoFinder != null || id == null || wireless.isActive() || !apps.injectorReady()) return;
        autoFinder = new WirelessFinder(this);
        autoFinder.start(r -> {
            if (!id.equals(r.id) || SystemClock.uptimeMillis() - lastAutoConnect < AUTO_CONNECT_PAUSE_MS) return;
            new Thread(() -> {
                ReceiverRow probe = new ReceiverRow(r.host, r.port, null);
                if (!reachable(probe)) return;
                runOnUiThread(() -> {
                    if (autoFinder == null || wireless.isActive()) return;
                    lastAutoConnect = SystemClock.uptimeMillis();
                    stopAutoReceiver();
                    // Anyone can announce the id: this connection only succeeds against a receiver that proves the token
                    startWireless(r.host, r.port, r.name, true);
                });
            }, "xdesk-receiver-probe").start();
        });
    }

    private void stopAutoReceiver() {
        if (autoFinder != null) autoFinder.stop();
        autoFinder = null;
    }

    private static final class ReceiverRow {
        final String host;
        final int port;
        final View view;

        ReceiverRow(String host, int port, View view) {
            this.host = host;
            this.port = port;
            this.view = view;
        }
    }

    private static boolean reachable(ReceiverRow row) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(row.host, row.port), RECEIVER_PROBE_TIMEOUT_MS);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Off the main thread. */
    private void probeReceiver(ReceiverRow row, Runnable afterwards) {
        final int visibility = reachable(row) ? View.VISIBLE : View.GONE;
        runOnUiThread(() -> {
            row.view.setVisibility(visibility);
            afterwards.run();
        });
    }

    private void startWireless(String host, int port, String name) {
        startWireless(host, port, name, false);
    }

    private void startWireless(String host, int port, String name, boolean auto) {
        if (!apps.injectorReady()) {
            Toast.makeText(this, R.string.xd_wireless_need_injector, Toast.LENGTH_LONG).show();
            return;
        }
        wireless.start(host, port, name, auto);
    }

    // --- 网页显示 ---

    /**
     * The browser's and the receivers' mouse and keyboard, as the phone's touchpad and keyboard would. Over X and a
     * Mac (targets that draw the pointer themselves) the pointer passes through DockPointer: its push against the
     * bottom opens the Dock (EdgePush), and the Dock has it while open. Over an Android app the app session's
     * injector hears the pointer itself; its moves and buttons go straight to the Router.
     */
    private final InputSink webInput = new InputSink() {
        @Override public void move(int dx, int dy) {
            if (receiverOverDrawn()) dockPointer.move(remoteIn, dx, dy);
            else remoteIn.move(dx, dy);
        }
        @Override public void moveToFraction(float x, float y) {
            if (!receiverOverDrawn()) {
                remoteIn.moveToFraction(x, y);
                return;
            }
            ExternalDock dock = externalDock;
            if (dock != null && dock.driven()) {
                // The Dock has the receiver's pointer: it moves the dock's arrow (the content's pointer stays
                // where it was, DISPLAY.md §7), and the receiver's clicks are the dock's
                dock.placeDriven(x, y);
                return;
            }
            remoteIn.moveToFraction(x, y);
            // A placed pointer stops at the edge of its picture: resting on the bottom row is all there is to
            // open the Dock by over X and a Mac (DISPLAY.md §7); over an app the session hears it itself
            receiverRest(x, y);
        }
        @Override public void pointerGone() {
            // Whichever it was on, the client may have changed since
            session.input().pointerGone();
            apps.input().pointerGone();
            apps.browserGone();
            updateStatus();
        }
        @Override public boolean anyThread() {
            // Only the Android injector takes input off the main thread; the switcher, X and a Mac do not
            return apps.isActive() && client == apps.input();
        }
        @Override public void androidButton(int button) {
            // 1 返回、2 主页是语义动作（INPUT.md §3.4），按目标去做；4 打开外接屏上的启动台（Dock 的"全部"），
            // 5 是切应用的一步（和手机三指横滑一次一样，PROTOCOL.md ANDROID）
            if (button == 1 || button == 2) remoteIn.action(button, 0);
            else if (button == 3) wireless.sendApps(appLines());
            else if (button == 4) showSwitcher(false);
            else if (button == 5) {
                remoteIn.action(io.github.n2ray.xdesk.input.Input.SWITCH_APP, 1);
                remoteIn.action(io.github.n2ray.xdesk.input.Input.SWITCH_APP, 0);
            }
        }
        @Override public void chooseApp(String key) {
            chooseKey(key);
        }
        @Override public void browserLayout(String layout) {
            apps.setBrowserLayout(layout);
            updateStatus();
        }
        @Override public void button(int b, boolean down) {
            // While the Dock is open the receiver's buttons are its, pressed on the Dock, up on the Dock
            if (receiverOverDrawn()) dockPointer.button(remoteIn, b, down);
            else remoteIn.button(b, down);
        }
        @Override public void click(int b) {
            if (receiverOverDrawn()) dockPointer.click(remoteIn, b);
            else remoteIn.click(b);
        }
        @Override public void scroll(int dy, int dx) {
            // While the Dock has the receiver's pointer its wheel is not the content's, as for the phone's mouse
            if (receiverOverDrawn() && dockPointer.scroll(dy, dx)) return;
            remoteIn.scroll(dy, dx);
        }
        @Override public boolean scrollFine(float dy, float dx, int phase) {
            // A receiver's trackpad (SCROLL_FINE): smooth over X, a gesture to a Mac, whole steps to the rest (Router)
            if (receiverOverDrawn() && dockPointer.scroll(dy, dx)) return true;
            return remoteIn.scrollFine(dy, dx, phase);
        }
        @Override public void key(int scan, boolean down) {
            // The receiver said what its keyboard is (KEYBOARD): tell the Router, which translates per target
            int kind = wireless.keyboardKind();
            if (kind != remoteKeyboard) {
                remoteKeyboard = kind;
                remoteIn.setMacKeyboard(kind == WirelessDisplay.KEYBOARD_MAC);
            }
            remoteIn.key(scan, down);
        }
        @Override public void tapKey(int scan) { remoteIn.tapKey(scan); }
        @Override public void text(String s) { remoteIn.text(s); }
        @Override public void touches(int action, int[] ids, float[] x, float[] y) {
            // TOUCH (34): every finger of XDesk Receiver to the Android app that takes touches. The receiver sends
            // them only in INPUT_MODE 2; without this they were dropped here and the app got nothing
            if (apps.isActive()) apps.input().touches(action, ids, x, y);
        }
        @Override public void touchMode(boolean tablet) {
            // TOUCH_MODE (35): the receiver's 像电脑 / 像平板 is the app's one choice, as the top button's. Saved for
            // the app, shown here, and sent back to every receiver as INPUT_MODE. An app that cannot switch says so
            if (!apps.canUseTouchMode()) {
                wireless.refuseTouchMode();
                return;
            }
            AppCatalog.App a = apps.app();
            if (a != null && apps.isTouchMode(a) != tablet) apps.setTouchMode(a, tablet);
            updateStatus();
        }
    };
    /** What the receiver's keyboard is (WirelessDisplay KEYBOARD) as the Router last heard it. */
    private int remoteKeyboard;

    /** The receiver's pointer over X or a Mac: targets that draw the pointer themselves, where DockPointer works. */
    private boolean receiverOverDrawn() {
        InputSink c = client;
        return c instanceof DesktopInput || c instanceof WindowScopedInput || c instanceof MacInput;
    }

    private final float[] remotePlace = new float[2];

    /** Feeds the dock what a placed receiver pointer resting at the bottom means over X and a Mac (DISPLAY.md §7). */
    private void receiverRest(float x, float y) {
        ExternalDock dock = externalDock;
        InputSink c = client;
        boolean at;
        if (c instanceof MacInput && c.pointerPlace(remotePlace)) {
            // The Mac picture is letterboxed on the screen: where the Mac's pointer stopped (clamped to the
            // picture) is the bottom, and its place across the screen, not the requested place
            at = remotePlace[1] >= 1f;
            x = remotePlace[0];
        } else {
            at = y >= 1f;
        }
        if (dock != null) dock.restDriven(at, x);
        // The notification centre's corner and bell, and the Mac menu's ≡, hear the same placed pointer
        if (notify != null) notify.placedAt(x, y);
        if (menus != null) menus.placedAt(x, y);
    }

    private WirelessFinder autoFinder;
    private boolean activityStarted;
    /** Local discovery is held while this window is started (Mac screens and MacClients hold it too). */
    private boolean discoveryHeld;
    private long lastAutoConnect = -AUTO_CONNECT_PAUSE_MS;
    private static final int AUTO_CONNECT_PAUSE_MS = 10000;
    private static final int RECEIVER_PROBE_MS = 2500, RECEIVER_PROBE_TIMEOUT_MS = 1200;
    /** How long the picker looks on the network before it stops and offers Search again. */
    private static final int RECEIVER_SEARCH_MS = 15000;

    private final WebServer.Listener webListener = new WebServer.Listener() {
        @Override public void onWebCode(String browserName, String code) {
            // The code the server gave the phone stays here: the sheet and Settings › Web display both show it
            webPairingBrowser = browserName;
            webPairingCode = code;
            refreshWebPageIfOpen();
            if (webCodeDialog != null) webCodeDialog.dismiss();
            TextView digits = Style.label(XDeskActivity.this, code, Style.TEXT, 56);
            digits.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            digits.setGravity(Gravity.CENTER);
            digits.setLetterSpacing(0.3f);
            digits.setPadding(0, dp(16), 0, dp(16));
            webCodeDialog = ui().sheet(getString(R.string.xd_web_code_title), getString(R.string.xd_web_code_note, browserName), digits);
            // A stray touch must not lose the code: it goes when the pairing ends (onWebCodeDone), at most 2 minutes
            webCodeDialog.setCancelable(false);
        }
        @Override public void onWebCodeDone() {
            webPairingBrowser = webPairingCode = null;
            refreshWebPageIfOpen();
            if (webCodeDialog != null) webCodeDialog.dismiss();
            webCodeDialog = null;
        }
        @Override public void onWebMessage(String message) {
            Toast.makeText(XDeskActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    private String webValue() {
        if (wireless.isWeb() && wireless.isActive()) return wireless.receiverName();
        return getString(web.isEnabled() ? R.string.xd_web_on : R.string.xd_wireless_off);
    }

    /** 网页显示：开关、浏览器要打开的地址（无线、热点、有线）、有线的设置、已配对的浏览器。 */
    private void addCopyRow(SettingsUi s, LinearLayout card, String title, final String text) {
        s.valueRow(card, title, text, getString(R.string.xd_web_copy), () -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("XDesk", text));
            Toast.makeText(this, getString(R.string.xd_web_copied, text), Toast.LENGTH_SHORT).show();
        });
    }

    private void showWebPage() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] d = new android.app.Dialog[1];
        if (wireless.isWeb() && wireless.isActive()) {
            // Orange and asked first: stopping takes the browser's picture away
            s.stopRow(c, getString(R.string.xd_wireless_stop, wireless.receiverName()), null, () ->
                s.confirm(getString(R.string.xd_web_stop_title), getString(R.string.xd_web_stop_message),
                    getString(R.string.xd_stop), SettingsUi.LEVEL_STOP, () -> {
                        d[0].dismiss();
                        wireless.stop();
                    }));
        }
        LinearLayout main = s.card(c);
        s.switchRow(main, getString(R.string.xd_web), getString(R.string.xd_web_switch_note), web.isEnabled(), on -> {
            web.setEnabled(on);
            d[0].dismiss();
            ui.postDelayed(this::showWebPage, 300);
        });
        if (web.isEnabled()) {
            LinearLayout where = s.section(c, getString(R.string.xd_web_addresses));
            java.util.List<WebServer.Address> list = WebServer.addresses();
            if (list.isEmpty()) s.infoRow(where, getString(R.string.xd_web_no_network), getString(R.string.xd_web_no_network_note), null);
            for (final WebServer.Address a : list) {
                String kind = getString(a.kind == WebServer.KIND_WIFI ? R.string.xd_web_kind_wifi
                    : a.kind == WebServer.KIND_HOTSPOT ? R.string.xd_web_kind_hotspot
                    : a.kind == WebServer.KIND_USB ? R.string.xd_web_kind_usb
                    : a.kind == WebServer.KIND_ETHERNET ? R.string.xd_web_kind_ethernet : R.string.xd_web_kind_other);
                s.addressRow(where, kind, a.url(), getString(R.string.xd_web_copy), () -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("XDesk", a.url()));
                    Toast.makeText(this, getString(R.string.xd_web_copied, a.url()), Toast.LENGTH_SHORT).show();
                });
            }
        }
        // SetWeb.dc.html: while a browser pairs, the page shows the same code the sheet does
        if (webPairingCode != null) {
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
            gp.topMargin = dp(10);
            c.addView(webPairingCard(), gp);
        }
        LinearLayout wired = s.section(c, getString(R.string.xd_web_wired));
        s.infoRow(wired, getString(R.string.xd_web_wired_how), getString(R.string.xd_web_wired_how_note), null);
        if (WebServer.usbTetheringIsNcm(this)) {
            // Already NCM: nothing to tap, so it is a plain row rather than a row that does nothing
            s.infoRow(wired, getString(R.string.xd_web_ncm), getString(R.string.xd_web_ncm_note), getString(R.string.xd_web_ncm_on));
        } else {
            s.valueRow(wired, getString(R.string.xd_web_ncm), getString(R.string.xd_web_ncm_note),
                getString(R.string.xd_web_ncm_off), () -> {
                if (WebServer.setUsbTetheringNcm(this)) {
                    Toast.makeText(this, R.string.xd_web_ncm_done, Toast.LENGTH_LONG).show();
                    d[0].dismiss();
                    ui.postDelayed(this::showWebPage, 300);
                } else {
                    s.permitSheet(getString(R.string.xd_permit_write_secure), getString(R.string.xd_web_ncm_why),
                        getString(R.string.xd_permit_write_secure_path), getString(R.string.xd_permit_copy), () -> {
                        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb shell settings put global tether_force_usb_functions 1"));
                    }, null);
                }
            });
        }
        s.linkRow(wired, getString(R.string.xd_web_tethering), null, "", this::openTetherSettings);
        LinearLayout computer = s.section(c, getString(R.string.xd_web_computer));
        // Two rows used to be called "How": this one is the computer side's
        s.infoRow(computer, getString(R.string.xd_web_computer_how), getString(R.string.xd_web_computer_note), null);
        addCopyRow(s, computer, getString(R.string.xd_web_adb), "adb forward tcp:" + WebServer.PORT + " tcp:" + WebServer.PORT);
        java.util.List<WebServer.Address> forSsh = WebServer.addresses();
        if (!forSsh.isEmpty()) {
            addCopyRow(s, computer, getString(R.string.xd_web_ssh),
                "ssh -N -L " + WebServer.PORT + ":127.0.0.1:" + WebServer.PORT + " -p 8022 " + forSsh.get(0).host);
        }
        int paired = web.pairedCount();
        if (paired > 0) {
            // Yellow: forgetting only means pairing again, nothing is removed (SetWeb)
            s.stopRow(c, getString(R.string.xd_web_forget, paired), null, () ->
                s.confirm(getString(R.string.xd_web_forget_title), getString(R.string.xd_web_forget_message),
                    getString(R.string.xd_web_forget_action), SettingsUi.LEVEL_DANGER, () -> {
                        web.forgetBrowsers();
                        if (wireless.isWeb() && wireless.isActive()) wireless.stop();
                        d[0].dismiss();
                    }));
        }
        d[0] = settingsPage(getString(R.string.xd_web), c, () -> {
            webPage = null;
            if (settingsRefresh != null) settingsRefresh.run();
        });
        webPage = d[0];
    }

    /** The pairing started or ended while Settings › Web display is open: the page says it now. */
    private void refreshWebPageIfOpen() {
        if (webPage == null || !webPage.isShowing() || isFinishing()) return;
        Dialog old = webPage;
        webPage = null;
        old.dismiss();
        showWebPage();
    }

    /** SetWeb.dc.html's green card: the browser pairing now and the code it must type. */
    private View webPairingCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Style.rounded(this, 0xFF13261D, 16));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        View dot = new View(this);
        dot.setBackground(Style.dot(Style.OK));
        LinearLayout.LayoutParams dp8 = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp8.rightMargin = dp(12);
        card.addView(dot, dp8);
        String text = getString(R.string.xd_web_pairing_now, webPairingBrowser, webPairingCode);
        android.text.SpannableString s = new android.text.SpannableString(text);
        int at = text.lastIndexOf(webPairingCode);
        if (at >= 0)
            s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), at, at + webPairingCode.length(), 0);
        TextView line = new TextView(this);
        line.setText(s);
        line.setTextColor(Style.TEXT);
        line.setTextSize(Style.TEXT_SMALL + 1);
        line.setLineSpacing(dp(2), 1f);
        card.addView(line, new LinearLayout.LayoutParams(0, -2, 1));
        return card;
    }

    /** Settings → Network → Hotspot and tethering; the component differs between ROMs. */
    private void openTetherSettings() {
        Intent[] tries = {
            new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            new Intent().setClassName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"),
            new Intent(Settings.ACTION_WIRELESS_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) { }
        }
    }

    /** What Devices (the launcher's device bar, Settings › Connect device) asks of the Linux environments: Stop, set up. */
    private final Devices.Actions deviceActions = new Devices.Actions() {
        @Override public void stopLinux() { confirmStop(); }
        @Override public void setUpLinux() { chooserHost.onTermuxHint(); }
        @Override public void stopBuiltin() {
            ui().confirm(getString(R.string.xd_bl_stop_confirm_title), getString(R.string.xd_bl_stop_note),
                getString(R.string.xd_stop), SettingsUi.LEVEL_STOP, () -> BuiltinLinux.get(XDeskActivity.this).stop());
        }
    };

    private final ChooserView.Host chooserHost = new ChooserView.Host() {
        @Override public void onDockTap(String key) { chooseKey(key); }
        @Override public void onItemLongPress(String key) { showItemCard(key); }
        @Override public void onDockToggle(String key) {
            List<String> dock = savedLines(PREF_DOCK);
            if (!dock.remove(key)) dock.add(key);
            saveDock(dock);
        }
        @Override public void onTouchpad() {
            showRunning();
            updateStatus();
        }
        @Override public void onToggleKeys() { ownership.toggle(); }
        @Override public void onSearchTouched() { beginSearchTyping(); }
        @Override public void onOpenSettings() { showSettings(); }
        @Override public void onChooseOutput() { chooseOutput(); }
        @Override public void onSessionChosen(final String name) {
            phoneKeys = true;
            // The built-in Linux's whole desktop: its own X, beside Termux's (no xdesk, no Termux state to ask)
            if (Devices.BUILTIN_SESSION.equals(name)) {
                openBuiltinDesktop();
                return;
            }
            if (session.stopping() != null) {
                Toast.makeText(XDeskActivity.this, R.string.xd_desktop_stopping, Toast.LENGTH_SHORT).show();
                return;
            }
            if (externalDisplay() == null) {
                needDisplayNotice();
                return;
            }
            // 选择界面刚读过；从触摸板这边切换时可能已经过时，先问一下
            if (launcherShown()) sessionDecided(name);
            else withFreshTermuxState(new Runnable() {
                @Override public void run() { sessionDecided(name); }
            });
        }
        private void sessionDecided(final String name) {
            leaveContentChoice();
            pendingApp = null;  // an Android app still waiting for its activation is no longer what was chosen
            // A Termux desktop chosen while the built-in Linux's X is shown: Termux's X comes back first (owner,
            // 22 November: once back on the built-in one, Termux's desktop could not be reached again; its session
            // ran, so it was only "shown as opened", on the built-in X)
            IBinder termux = termuxX();
            if (termux != null && XServers.isBuiltin(XServers.shown())) XServers.switchTo(termux);
            // Only a whole desktop is chosen here (整个桌面, the one thing that starts an environment's XFCE). "NAME+apps",
            // what Linux programs run on, is started by needDesktop alone and is never listed as recent
            boolean appsOnly = name.endsWith(Devices.APPS_ONLY);
            if (!appsOnly) rememberRecent(ChooserView.recentKey(name));
            if (name.equals(runningSession)) {
                if (session.isActive()) {
                    desktopName = name;
                    showOpened();
                    updateStatus();
                } else {
                    reattach();
                }
            } else if (runningSession != null && runningSession.endsWith(Devices.APPS_ONLY)) {
                // Only X and a window manager run, its own or another environment's: the whole desktop comes in their
                // place (xdesk switch). X and the open programs stay, so there is nothing to ask
                launchDesktop(name, true);
            } else if (runningSession != null) {
                // One X server, one desktop session (DEVICES.md §2): another whole desktop ends first, after asking
                ui().confirm(getString(R.string.xd_switch_desktop_title), getString(R.string.xd_switch_desktop, runningSession),
                    getString(R.string.xd_switch), SettingsUi.LEVEL_STOP, new Runnable() {
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
        @Override public void onNoDisplayHint() {
            showWirelessPicker();
        }
        @Override public void onTermuxHint() {
            if (installed(PKG_TERMUX) && checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
                showPermitSetup(getString(R.string.xd_permit_run_command), getString(R.string.xd_permit_run_command_why),
                    getString(R.string.xd_permit_run_command_path), null, v -> requestPermissions(new String[]{PERM}, REQ_LIST_SESSIONS));  // the page's own "back to the list" stays below
            } else {
                refreshTermux();
            }
        }
    };

    /** The Launchpad mode's grid (LaunchpadView): the phone's home page there. */
    private final LaunchpadView.Host launchpadHost = new LaunchpadView.Host() {
        @Override public void onLaunchpadOpen(String key) {
            // The list's way of opening (Switchboard.open, then chooseKey); the phone stays where it is (showOpened
            // keeps the grid in this mode). A tap must not take the external screen's keyboard focus: the same
            // phonePassive/refocusExternal path the list uses (updatePhoneFocus is what makes it passive at all).
            Switchboard.get(XDeskActivity.this).open(Switchboard.PHONE, key);
            updatePhoneFocus();
            if (phonePassive) refocusExternal();
        }
        @Override public void onItemLongPress(String key) { showItemCard(key); }
        @Override public void onTouchpad() {
            showRunning();
            updateStatus();
        }
        @Override public void onToggleKeys() { ownership.toggle(); }
        @Override public void onSearchTouched() { beginSearchTyping(); }
        @Override public void onChooseOutput() { chooseOutput(); }
        @Override public void onDim() { setDimmed(true); }
        @Override public void onOpenSettings() { showSettings(); }
    };

    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) {
            applyDefaultPage();
            updateCapture();
            applyKeysButton();
            // A keyboard came: whether the X side has an input method for it is asked again (xdesk status)
            InputDevice d = InputDevice.getDevice(id);
            if (session.isActive() && d != null && d.isExternal() && !d.isVirtual() && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                ui.post(syncTermux);
        }
        @Override public void onInputDeviceRemoved(int id) {
            // A key or button held on a keyboard or mouse that went away would stay down on the target (§2 cancel)
            keysIn.cancel();
            mouseIn.cancel();
            heldModifiers.clear();
            if (keyView != null) keyView.clearMouse();
            // The last keyboard or mouse went: their button goes too, and they belong to the display again, or a
            // 键鼠在本机 left behind would keep the home Launchpad away with no button to undo it
            if (!hasExternalInput()) ownership.set(true);
            applyKeysButton();
            applyDefaultPage();
            updateCapture();
        }
        @Override public void onInputDeviceChanged(int id) { }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override public void run() {
            checkServerBuild();
            if (!session.isConnected()) showConnectFailure();
        }
    };

    /** The X server already asked about its build, so each server is asked (and the user prompted) once. */
    private android.os.IBinder buildAsked;
    /** After xdesk stop: start the same desktop again (an old X server's restart). */
    private boolean restartOldServer;
    private String restartName;

    /**
     * An X server started before XDesk was updated keeps the old APK's code and doesn't work with this app (a black
     * desktop, XServerBuild): ask once per server whether to restart the desktop. Never restarts without asking.
     */
    private void checkServerBuild() {
        final ICmdEntryInterface s = MainActivity.verifiedServer;
        if (s == null || s.asBinder() == buildAsked) return;
        buildAsked = s.asBinder();
        final String mine = CmdEntryPoint.buildId(); // here: its class init makes a Handler, which needs a Looper
        new Thread(() -> {
            if (XServerBuild.differs(s, mine)) ui.post(() -> askRestartOldServer(s));
        }, "xdesk-xbuild").start();
    }

    private void askRestartOldServer(ICmdEntryInterface s) {
        if (isFinishing() || isDestroyed() || MainActivity.verifiedServer != s || session.stopping() != null) return;
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            ui().confirm(getString(R.string.xd_xbuild_title), getString(R.string.xd_xbuild_no_permission), getString(R.string.xd_ok), null);
            return;
        }
        ui().confirm(getString(R.string.xd_xbuild_title), getString(R.string.xd_xbuild_message), getString(R.string.xd_xbuild_restart),
            SettingsUi.LEVEL_STOP, getString(R.string.xd_xbuild_later), () -> {
                if (session.stopping() != null) return;
                restartName = runningSession != null ? runningSession : desktopName;
                restartOldServer = true;
                stopDesktop();
            });
    }

    /**
     * A content opened on the external display (a Mac's screen) may have taken the focused display: the phone's
     * window then has neither the keyboard nor the captured mouse. (A Mac's screen takes no focus itself, but it is
     * the focused app of the display on top, and Android then gives the focus to no window at all.) Take it back:
     * soon, so the keyboard is not dead for seconds after a Mac opens, and looked at again twice, as a take-back
     * that came before the content's window was up is undone by it.
     */
    private final Runnable regainFocus = new Runnable() {
        @Override public void run() {
            // Not from the phone's list worked by finger alone. With a keyboard or mouse on the phone the list is the
            // phone's page while the content runs (showOpened), and those need this window's focus to reach the content
            boolean list = launcherShown() && !(externalDisplay() != null && hasExternalInput() && !phoneTyping());
            if (contentKey == null || !activityStarted || !phoneForwards() || list || SwitcherOverlay.isShown()) return;
            if (!hasWindowFocus()) {
                Log.d(TAG, "focus: taking it back from the content's screen");
                try {
                    Intent self = new Intent(XDeskActivity.this, XDeskActivity.class);
                    self.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(self);
                } catch (RuntimeException e) {
                    Log.w(TAG, "focus", e);
                }
            }
            if (--regainTries > 0) ui.postDelayed(this, REGAIN_AGAIN_MS);
        }
    };
    private int regainTries;
    private static final long REGAIN_FIRST_MS = 400, REGAIN_AGAIN_MS = 1200;

    private final Runnable recapture = new Runnable() {
        @Override public void run() { updateCapture(); }
    };

    private final Runnable dim = new Runnable() {
        @Override public void run() { setDimmed(true); }
    };
    /** Dimmed.dc.html: the way back, very dim in the middle of the phone screen; moved now and then (burn-in). */
    private View dimHint;
    private int dimMoves;
    private static final long DIM_MOVE_MS = 60_000;
    /** Dimmed.dc.html: the hint shows for a few seconds after dimming, and after each move (the screen stays dim). */
    private static final long DIM_HINT_MS = 4_000;
    private final Runnable dimHintHide = new Runnable() {
        @Override public void run() {
            if (dimmed && dimHint != null) dimHint.setVisibility(View.GONE);
        }
    };
    private final Runnable dimMove = new Runnable() {
        @Override public void run() {
            if (!dimmed || dimHint == null) return;
            // A few small places, one step every minute: nothing on the panel stays in one spot long
            int[][] steps = {{-6, -4}, {5, 3}, {-4, 6}, {7, -5}, {0, 0}};
            int[] at = steps[dimMoves++ % steps.length];
            dimHint.setTranslationX(dp(at[0]));
            dimHint.setTranslationY(dp(at[1]));
            showDimHint();
            ui.postDelayed(this, DIM_MOVE_MS);
        }
    };

    private void showDimHint() {
        if (dimHint == null) return;
        dimHint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(dimHintHide);
        ui.postDelayed(dimHintHide, DIM_HINT_MS);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // The built-in Linux opens its desktop here once it runs (BuiltinLinux.start)
        BuiltinLinux.attachHook = this::builtinStarted;
        BuiltinLinux.registerDebug(this);
        BuiltinLinux.get(this).addListener(builtinChanged);
        // Termux's X and the built-in Linux's run side by side; the window shows one (XServers)
        XServers.listener = xServers;
        // While the desktop has the keyboard its window gets the keys: Ctrl+Alt+M there gives them back
        MainActivity.keyHook = new MainActivity.KeyHook() {
            @Override public boolean onKey(KeyEvent e) {
                InputDevice d = e.getDevice();
                return d != null && d.isExternal() && ownership.onKey(e);
            }
        };
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setStatusBarColor(Style.BACKGROUND);
        getWindow().setNavigationBarColor(Style.BACKGROUND);
        displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Contents.setHost(contentsHost);
        // Paired Macs as contents (MacSource): reads the saved list, connects nothing yet
        MacSource.start(this);
        ScreenShareSource.start(this);
        MacLogin.host = loginHost;
        ShareHub.get(this);  // one clipboard for every device from the start: its listener, the Linux desktop's copies
        MacSource.switcher = prefix -> showSwitcher(true);
        LinuxSource.install(this); // "l:" Linux programs and X windows
        Devices.get(this).setActions(deviceActions); // DEVICES.md: connect/disconnect of the Linux environments
        Devices.get(this).addListener(keyModeFollows); // the X side's input method, a Linux device's keyboard switch
        inputs = (InputManager) getSystemService(INPUT_SERVICE);
        ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        cover = new ExternalCover(this);
        prefs = getPreferences(MODE_PRIVATE);
        // Read before the 0.7.0 migrations and first writes: these were here before this version
        oldPrefsAtStart = hasPrefsFromBefore070();
        migrateDimPref();
        // Programs hidden by default (DEVICES.md §1): the favourites from before stay favourites
        Devices.get(this).keepFavouritesShown(savedLines(PREF_DOCK));
        // The Launchpad mode's home is the grid, also when a session is already running at start
        launchpadPage = launchpadMode();
        session = DesktopSession.get(this);
        apps = AppSession.get(this);
        appForwarded = apps.isForwarding();  // the session may outlive this window
        catalog = AppCatalog.get(this);
        Switchboard.get(this).setHost(boardHost);
        // The split-stage prototype's comings and goings (DISPLAY.md §15): input and the home follow them
        SplitStageActivity.observe(() -> ui.post(this::splitStageChanged));
        audioRoute = AudioRoute.get(this);
        wireless = WirelessDisplay.get(this);
        updateReceiverSettings();
        externalDock = new ExternalDock(this, () -> apps.raiseCursor(), id -> apps.imeTop(id));
        apps.setBottomEdgeListener(externalDock);
        apps.setPointerUseListener(() -> { ExternalDock d = externalDock; if (d != null) d.pointerUsed(); });
        notify = Notify.get(this);
        notify.attachActivity(this);
        notify.setRaiseAppCursor(() -> apps.raiseCursor());  // over an Android app its cursor stays above the centre
        notify.setActions(sideActions);
        apps.setCornerEdgeListener(notify);
        menus = MacMenus.get(this);
        if (prefs.contains(PREF_OUTPUT)) audioRoute.migrateOldChoice(prefs.getInt(PREF_OUTPUT, 0));
        applyOrientation();
        lastOrientation = getResources().getConfiguration().orientation;
        setContentView(buildUi());
        screens.getViewTreeObserver().addOnGlobalLayoutListener(this::fitToKeyboard);
        session.setListener(sessionListener);
        apps.setListener(appListener);
        audioRoute.setListener(audioListener);
        wireless.setListener(wirelessListener);
        // A driven Dock's arrow is in the picture: a receiver hides its own pointer then (INPUT_MODE 3)
        wireless.setDockDriven(() -> {
            ExternalDock d = externalDock;
            return d != null && d.driven();
        });
        wireless.setWebInput(webInput);
        web = WebServer.get(this);
        web.setListener(webListener);
        web.start();
        audioRoute.setWireless(wireless.capturesSound());
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
        // Started from the running notification's action: the Launchpad, or the end (all fields are up by now)
        if (handleServiceIntent(getIntent())) return;
        // A recreation replays the old intent; its link was already offered once
        if (b == null) handlePairingLink(getIntent());
        ui.post(this::maybeShowPermissionOnboard);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!handleServiceIntent(intent)) handlePairingLink(intent);
        setIntent(intent);
    }

    /**
     * The running notification's actions (PhoneNotify.dc.html): the Launchpad on the display, or the existing end.
     * @return true when the activity ended and must do nothing more
     */
    private boolean handleServiceIntent(Intent intent) {
        if (intent == null) return false;
        if (intent.getBooleanExtra(DesktopService.EXTRA_END, false)) {
            intent.removeExtra(DesktopService.EXTRA_END);
            // confirmEndXDesk's work, without a question: the action already said what it does
            releaseLatches();
            client = null;
            router.cancelAll();
            DesktopService.endAll(this);
            finishAndRemoveTask();
            return true;
        }
        if (intent.getBooleanExtra(DesktopService.EXTRA_LAUNCHPAD, false)) {
            intent.removeExtra(DesktopService.EXTRA_LAUNCHPAD);
            ui.post(() -> showSwitcher(false));  // the Dock's All / the Launchpad action
        }
        return false;
    }

    /** A tapped sill://pair link (a camera app read its QR code): confirm the Mac and pair, on the phone. */
    private void handlePairingLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        // Relaunched from Recents: the task's base intent is replayed, never pair the same link twice
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        String raw = intent.getData().toString();
        intent.setData(null);   // one shot: never pair again on the next onNewIntent
        MacSettings.fromLink(this, raw);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // Back in front: the idle timer starts again (onPause took it, and a dimmed window, away)
        bumpIdle();
        checkServerBuild();
        // NOTIFY.md §2.1: granted but not bound (HyperOS refused the system's rebind): ask while XDesk is in front
        NotifyCenter.get(this).rebindIfNeeded();
        // Back from a system page: permission cards whose permission came say so (the rebind takes a moment)
        PermitCard.recheck();
        ui.postDelayed(PermitCard::recheck, 1500);
        ui.postDelayed(PermitCard::recheck, 4500);
        // The permission pages say what is true now; a lost required permission is told once, after the injector had
        // its moment to come back (the delayed checks judge it, not the immediate one)
        refreshPermissionPages();
        ui.post(() -> checkPermissionAlerts(false));
        ui.postDelayed(() -> checkPermissionAlerts(false), 2500);
        ui.postDelayed(() -> checkPermissionAlerts(true), 5000);
        // Back on this window, perhaps from a terminal where the X side's input method was set up or stopped
        if (session.isActive()) ui.post(syncTermux);
        ensureDashboard();
        apps.activateIfPaired();
        // 从系统设置或 Shizuku 授权回来、或装好缺的 App 回来时接着走
        resumeRunCommand();
        if (pendingApp != null) {
            startApp(pendingApp);
            return;
        }
        if (apps.isActive()) return;
        if (!session.isActive()) {
            if (launcherShown()) refreshChooser();
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
        activityStarted = false;
        syncDesktopKeyboard();
        if (discoveryHeld) {
            discoveryHeld = false;
            MacDiscovery.release();
        }
        stopAutoReceiver();
        Display d = externalDisplay();
        // A Mac on the screen is covered too (owner, 2026-11-19: the Mac desktop stayed in view and heard)
        boolean showing = apps.isActive() || session.isActive() || com.termux.x11.mac.MacActivity.anyShowing();
        if (d == null || TargetDisplay.isCar(d) || !prefs.getBoolean(PREF_COVER, true) || !showing) return;
        cover.show(d);
        if (cover.isShown()) {
            forwardingBeforeCover = apps.isForwarding();
            if (forwardingBeforeCover) apps.setForwarding(false);
            // 盖着的桌面不该收到打的字
            takenBeforeCover = desktopTaken;
            if (takenBeforeCover) setDesktopTaken(false);
            apps.setHotkeyAllowed(false);
            // Privacy covers the sound too (owner, 2026-11-19): XDesk's own players go quiet, the apps' sound is off
            SoundCover.set(true);
            apps.muteApps(true);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        removeCover();
        activityStarted = true;
        syncDesktopKeyboard();
        // Only a saved Mac with a recognition key can be found at home: no such Mac, no mDNS
        if (!discoveryHeld && MacStore.anyFindableAtHome(this)) {
            discoveryHeld = true;
            MacDiscovery.acquire(this);
        }
        startAutoReceiver();
    }

    private void removeCover() {
        if (!cover.isShown()) return;
        cover.hide();
        SoundCover.set(false);
        apps.muteApps(false);
        apps.setHotkeyAllowed(true);
        if (forwardingBeforeCover) apps.setForwarding(true);
        forwardingBeforeCover = false;
        if (takenBeforeCover && session.isActive()) setDesktopTaken(true);
        takenBeforeCover = false;
        if (phonePassive) refocusExternal();
    }

    @Override protected void onPause() {
        resumed = false;
        // A timer left behind would dim the phone's window while another app is in front, and it would stay dim
        ui.removeCallbacks(dim);
        if (dimmed) setDimmed(false);
        dimTouchSwallowed = false;
        // Fingers leaving with the window would hold their buttons down
        if (gamepadShown) gamepadView.releaseAll();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (MacLogin.host == loginHost) {
            MacLogin.close(true);
            MacLogin.host = null;
        }
        BuiltinLinux.attachHook = null;
        BuiltinLinux.get(this).removeListener(builtinChanged);
        if (XServers.listener == xServers) XServers.listener = null;
        // The pages own threads and listeners (the wireless picker's probe): closing them ends those
        closeSettings();
        if (discoveryHeld) {
            discoveryHeld = false;
            MacDiscovery.release();
        }
        if (Contents.host() == contentsHost) Contents.setHost(null);
        SwitcherOverlay.hide(null);
        if (MacSource.switcher != null) MacSource.switcher = null;
        if (Devices.get(this).actions() == deviceActions) Devices.get(this).setActions(null);
        Devices.get(this).removeListener(keyModeFollows);
        MainActivity.keyHook = null;
        // onStop took the hotkey and the input away for the cover. The session outlives this window, so give them back
        removeCover();
        if (externalDock != null) externalDock.setDisplay(null);
        if (notify != null) {
            notify.setDisplay(null);
            notify.attachActivity(null);
            notify.setRaiseAppCursor(null);
            notify.setActions(null);
        }
        if (menus != null) menus.setDisplay(null);
        if (Switchboard.get(this).host() == boardHost) Switchboard.get(this).setHost(null);
        ui.removeCallbacks(retakeDesktop);
        // 换语言等会重建这个窗口，新的不知道桌面被接管了：先交还，键盘焦点和输入法回手机
        if (desktopTaken) {
            desktopTaken = false;
            MainActivity.setTakeMouse(false);
            Display d = desktopDisplay();
            if (d != null) apps.setImeOnDisplay(d.getDisplayId(), false);
        }
        displays.unregisterDisplayListener(displayListener);
        SplitStageActivity.observe(null);  // the stage, if it outlives this window, gets along without it
        CarActivity.setListener(null);
        inputs.unregisterInputDeviceListener(deviceListener);
        ui.removeCallbacksAndMessages(null);
        session.setListener(null);
        apps.setListener(null);
        // AppSession outlives the activity: nothing of this one may stay in it
        apps.setBottomEdgeListener(null);
        apps.setCornerEdgeListener(null);
        apps.setPointerUseListener(null);
        audioRoute.setListener(null);
        wireless.setListener(null);
        wireless.setDockDriven(null);
        wireless.setWebInput(null);
        // The server stays: this window is recreated on a language change. Without a listener it turns browsers away
        web.setListener(null);
        if (webCodeDialog != null) webCodeDialog.dismiss();
        super.onDestroy();
    }


    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        syncDesktopKeyboard();
        if (focus) {
            if (padFullscreen || gamepadShown) applyImmersive();
            // 选择界面开着时焦点归搜索框
            if (!launcherShown()) keyView.requestFocus();
            updateCapture();
            // After Android's own handling of the focus: the input method and the text bridge on one connection again
            ui.removeCallbacks(reconnectIme);
            ui.post(reconnectIme);
        }
    }

    private final Runnable reconnectIme = () -> {
        if (keyView != null && keyView.isFocused()) keyView.bridge().reconnect();
    };

    // --- 界面 ---

    private int dp(float v) {
        return Style.dp(this, v);
    }

    /** Settings › General › Phone interface: the Launchpad (grid first) or the console (touchpad first, the default). */
    private boolean launchpadMode() {
        return UI_LAUNCHPAD.equals(prefs.getString(PREF_PHONE_UI, UI_CONSOLE));
    }

    /** The phone shows its launcher (the console's list, or the Launchpad grid). */
    private boolean launcherShown() {
        return chooser != null && chooser.getVisibility() == View.VISIBLE
            || launchpad != null && launchpad.getVisibility() == View.VISIBLE;
    }

    /** A line on every launcher (the list's notice, and the Launchpad grid's line above it). */
    private void setLauncherNotice(String text) {
        setLauncherNotice(text, null);
    }

    /** The same, with what a tap does (the no-display line opens the receiver picker on both launchers). */
    private void setLauncherNotice(String text, Runnable action) {
        boolean changed = text != null ? !text.equals(launcherNotice) : launcherNotice != null;
        launcherNotice = text;
        launcherNoticeAction = text != null ? action : null;
        // A card that drops in and goes by itself (PhoneNotice), not a line fixed on the launchers (owner, 24 November).
        // Only a new text shows it: the launchers being built again (a turn, a new mode) call this with the same one
        if (chooser != null) chooser.setNotice(null);
        if (launchpad != null) launchpad.setNotice(null, null);
        if (phoneNotice == null) phoneNotice = new PhoneNotice(this);
        if (text == null) phoneNotice.hide();
        else if (changed) phoneNotice.show(text, launcherNoticeAction);
    }

    private PhoneNotice phoneNotice;

    /**
     * Settings › General › Phone interface was changed: the running screen (its Launchpad pill) is built again at
     * once, and the launcher showing, if any, becomes the new mode's. The touchpad page stays the touchpad page.
     */
    private void applyPhoneUi() {
        if (screens == null || chooser == null || launchpad == null) return;
        boolean home = launcherShown();
        chooserTyping = false;
        ui.removeCallbacks(searchFocus);
        ime.hideSoftInputFromWindow(chooser.getWindowToken(), 0);
        ime.hideSoftInputFromWindow(launchpad.getWindowToken(), 0);
        launcherWasDefault = launcherIsDefault();
        rebuildRunning();
        if (home) {
            if (launchpadMode()) showLaunchpad();
            else showChooserPage();
        } else {
            chooser.setVisibility(View.GONE);
            launchpad.setVisibility(View.GONE);
        }
        updateChooserTop();
        applyFullscreen();
        updatePhoneFocus();
        updateCapture();
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
        chooser.setVisibility(View.GONE);
        screens.addView(chooser, new FrameLayout.LayoutParams(-1, -1));
        launchpad = new LaunchpadView(this, launchpadHost);
        launchpad.setVisibility(View.GONE);
        screens.addView(launchpad, new FrameLayout.LayoutParams(-1, -1));
        screens.addView(setupScreen, new FrameLayout.LayoutParams(-1, -1));
        setupScreen.setVisibility(View.GONE);
        keyView = new KeyInputView(this, keySink);
        keyView.bridge().setCorrections(prefs.getBoolean(PREF_IME_CORRECTIONS, true));
        MacInput.translateModifiers = prefs.getBoolean(PREF_TRANSLATE_MODIFIERS, true);
        MacInput.keysViaIme = prefs.getBoolean(PREF_KEYS_VIA_IME, true);
        mouseSpeed = prefs.getFloat(PREF_MOUSE_SPEED, 1f);
        screens.addView(keyView, new FrameLayout.LayoutParams(1, 1));
        dimHint = buildDimHint();
        screens.addView(dimHint, new FrameLayout.LayoutParams(-1, -1));
        return screens;
    }

    /**
     * 已调暗时屏幕中间的一句话：碰一下恢复亮度。很小的字、很暗，过一分钟换一点位置防烧屏。
     * 它不点不碰，唤醒的那一下由 dispatchTouchEvent 吃掉。
     */
    private View buildDimHint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(false);
        box.setFocusable(false);
        box.setVisibility(View.GONE);
        ImageView moon = new ImageView(this);
        moon.setImageResource(R.drawable.xd_ic_moon);
        moon.setColorFilter(Style.FAINT);
        box.addView(moon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView line = Style.label(this, getString(R.string.xd_dim_hint), Style.FAINT, 14);
        line.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        box.addView(line, lp);
        TextView under = Style.label(this, getString(R.string.xd_dim_hint_note), Style.FAINT, 11);
        under.setGravity(Gravity.CENTER);
        under.setAlpha(0.6f);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-2, -2);
        up.topMargin = dp(4);
        box.addView(under, up);
        return box;
    }

    private View buildSetupScreen() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER);
        s.setBackgroundColor(Style.BACKGROUND);
        s.setClickable(true);  // 挡住下面的触摸板，启动时误碰不会点到桌面
        s.setPadding(dp(32), dp(32), dp(32), dp(32));

        setupBrand = new TextView(this);
        setupBrand.setText("XDesk");
        setupBrand.setTextColor(Style.ACCENT);
        setupBrand.setTextSize(15);
        setupBrand.setTypeface(Typeface.DEFAULT_BOLD);
        setupBrand.setLetterSpacing(0.1f);
        setupBrand.setGravity(Gravity.CENTER);
        s.addView(setupBrand, new LinearLayout.LayoutParams(-2, -2));

        setupKey = new ImageView(this);
        setupKey.setImageDrawable(new PermitCard.KeyIcon(Style.WARN));
        setupKey.setVisibility(View.GONE);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(dp(40), dp(40));
        kp.topMargin = dp(24);
        s.addView(setupKey, kp);

        // The failure page's round red icon (ConnectFail.dc.html): the reason at a glance, before the words
        setupIconBox = new FrameLayout(this);
        setupIconBox.setVisibility(View.GONE);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Style.DANGER_TINT);
        setupIconBox.setBackground(circle);
        setupIcon = new ImageView(this);
        setupIcon.setImageResource(R.drawable.xd_ic_link_off);
        setupIcon.setColorFilter(Style.DANGER);
        setupIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        setupIconBox.addView(setupIcon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(64), dp(64));
        ip.topMargin = dp(24);
        s.addView(setupIconBox, ip);

        setupSpinTop = new ProgressBar(this);
        setupSpinTop.setVisibility(View.GONE);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(dp(44), dp(44));
        stp.topMargin = dp(8);
        s.addView(setupSpinTop, stp);

        setupTitle = new TextView(this);
        setupTitle.setTextColor(Style.TEXT);
        setupTitle.setTextSize(20);
        setupTitle.setTypeface(Typeface.DEFAULT_BOLD);
        setupTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(16);
        s.addView(setupTitle, tp);

        setupMessage = new TextView(this);
        setupMessage.setTextColor(Style.TEXT_DIM);
        setupMessage.setTextSize(14);
        setupMessage.setGravity(Gravity.CENTER);
        setupMessage.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(12);
        s.addView(setupMessage, mp);

        // "xdesk start" in a mono box (Waiting.dc.html)
        setupCode = new TextView(this);
        setupCode.setTextColor(Style.TEXT_SOFT);
        setupCode.setTextSize(14);
        setupCode.setTypeface(Typeface.MONOSPACE);
        setupCode.setGravity(Gravity.CENTER);
        setupCode.setPadding(dp(12), 0, dp(12), 0);
        setupCode.setBackground(Style.rounded(this, Style.SURFACE, 12));
        setupCode.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(46));
        cp.topMargin = dp(10);
        s.addView(setupCode, cp);

        setupNote2 = new TextView(this);
        setupNote2.setTextColor(Style.TEXT_DIM);
        setupNote2.setTextSize(12);
        setupNote2.setGravity(Gravity.CENTER);
        setupNote2.setVisibility(View.GONE);
        LinearLayout.LayoutParams n2p = new LinearLayout.LayoutParams(-2, -2);
        n2p.topMargin = dp(10);
        s.addView(setupNote2, n2p);

        setupProgress = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(44), dp(44));
        pp.topMargin = dp(20);
        s.addView(setupProgress, pp);

        setupAction = Style.button(this, "", Style.ACCENT, 14, 12);
        setupAction.setTypeface(Typeface.DEFAULT_BOLD);
        setupAction.setPadding(dp(28), 0, dp(28), 0);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(44));
        ap.topMargin = dp(24);
        s.addView(setupAction, ap);

        setupSecond = Style.button(this, "", Style.SURFACE_RAISED, 14, 12);
        setupSecond.setPadding(dp(28), 0, dp(28), 0);
        setupSecond.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(44));
        sp.topMargin = dp(12);
        s.addView(setupSecond, sp);

        setupBack = Style.button(this, getString(R.string.xd_back_to_launcher), Style.SURFACE_RAISED, 14, 12);
        setupBack.setPadding(dp(28), 0, dp(28), 0);
        setupBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { backToChooser(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(44));
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
        fullscreenBackBtn = fullscreenKeyboardBtn = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BACKGROUND);
        root.setPadding(dp(Style.EDGE), dp(4), dp(Style.EDGE), dp(Style.EDGE));

        statusDot = new View(this);
        statusText = Style.label(this, "", Style.TEXT, Style.TEXT_SMALL + 1);
        statusText.setMaxLines(2);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setPadding(dp(8), 0, dp(8), 0);
        buildTopButtons();
        runningDock = new DockBar(this, chooserHost, false);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (landscape) {
            // 横屏高度最值钱：状态、收藏和按钮共一行
            topBar = top;
            if (launchpadMode()) {
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, dp(36));
                pp.rightMargin = dp(6);
                top.addView(launchpadPill(), pp);
            }
            top.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            top.addView(statusText, new LinearLayout.LayoutParams(dp(128), -2));
            statusRow = null;
            top.addView(runningDock, new LinearLayout.LayoutParams(0, dp(Style.TARGET), 1));
            addTopButtons(top, false);
            root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        } else {
            // 竖屏放不下一行，状态单独占一行
            topBar = null;
            LinearLayout status = new LinearLayout(this);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(dp(4), dp(2), 0, dp(4));
            if (launchpadMode()) {
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, dp(36));
                pp.rightMargin = dp(8);
                status.addView(launchpadPill(), pp);
            }
            status.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            status.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
            statusRow = status;
            root.addView(status, new LinearLayout.LayoutParams(-1, -2));
            addTopButtons(top, true);
            root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        }

        diagText = Style.label(this, "", Style.TEXT_DIM, Style.TEXT_TINY + 1);
        diagText.setPadding(dp(8), 0, dp(8), dp(4));
        root.addView(diagText, new LinearLayout.LayoutParams(-1, -2));
        applyDiagVisibility();

        if (!landscape) {
            LinearLayout.LayoutParams dock = new LinearLayout.LayoutParams(-1, dp(Style.TARGET + 4));
            dock.topMargin = dp(4);
            root.addView(runningDock, dock);
        }

        // The function keys open above the touch area, never over it: the pad keeps the bottom for the thumbs.
        keysPanel = buildKeysPanel();
        LinearLayout.LayoutParams kpp = new LinearLayout.LayoutParams(-1, -2);
        kpp.topMargin = dp(Style.GAP);
        root.addView(keysPanel, kpp);
        View center = buildTouchArea();
        backBtn = new Style.IconButton(this, R.drawable.xd_ic_back, getString(R.string.xd_back_app), Style.SURFACE);
        // 安卓的 Back，给外接屏上的应用。手机上它是边缘滑动，有些应用不留返回按钮
        backBtn.onTap(new Runnable() {
            @Override public void run() { apps.sendBack(); }
        });
        Style.IconButton keyboard = new Style.IconButton(this, R.drawable.xd_ic_keyboard, getString(R.string.xd_keyboard), Style.SURFACE);
        keyboardBtn = keyboard;
        keyboard.onTap(new Runnable() {
            @Override public void run() { toggleKeyboard(); }
        });
        // 不盖在触控区上：触控模式下那里的每个点都对应外接屏
        keysHandle = new Style.IconButton(this, R.drawable.xd_ic_chevron_down, getString(R.string.xd_keys), Style.SURFACE);
        keysHandle.onTap(new Runnable() {
            @Override public void run() {
                keysExpanded = !keysExpanded;
                applyKeysVisibility();
            }
        });
        leftButton = mouseButton(getString(R.string.xd_left_click), 1);
        rightButton = mouseButton(getString(R.string.xd_right_click), 3);
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
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        cp.leftMargin = cp.topMargin = cp.rightMargin = cp.bottomMargin = dp(8);
        full.addView(center, cp);
        fullscreenBtn.setVisibility(View.GONE);
        addCornerIcon(full, R.drawable.xd_ic_close, getString(R.string.xd_exit_fullscreen), Gravity.TOP | Gravity.END, new Runnable() {
            @Override public void run() { setFullscreenWanted(false); }
        });
        // Both are there in either orientation; which one shows follows what is on the display now.
        fullscreenBackBtn = addCornerIcon(full, R.drawable.xd_ic_back, getString(R.string.xd_back_app), Gravity.TOP | Gravity.START, new Runnable() {
            @Override public void run() { apps.sendBack(); }
        });
        fullscreenKeyboardBtn = addCornerIcon(full, R.drawable.xd_ic_keyboard, getString(R.string.xd_keyboard), Gravity.BOTTOM | Gravity.END, new Runnable() {
            @Override public void run() { toggleKeyboard(); }
        });
        applyFullscreenCorners();
        // 系统只认每条边最靠下的 200dp，手势条也排除不了。这里能拦多少算多少
        full.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
            v.setSystemGestureExclusionRects(java.util.Collections.singletonList(new android.graphics.Rect(0, 0, r - l, b - t))));
        return full;
    }

    /** The corners of the full-screen pad: Back for an Android app, the keyboard for everything else. */
    private void applyFullscreenCorners() {
        boolean app = apps.isActive();
        if (fullscreenBackBtn != null) fullscreenBackBtn.setVisibility(app ? View.VISIBLE : View.GONE);
        if (fullscreenKeyboardBtn != null) fullscreenKeyboardBtn.setVisibility(app ? View.GONE : View.VISIBLE);
    }

    private Style.IconButton addCornerIcon(FrameLayout parent, int icon, String label, int gravity, Runnable action) {
        Style.IconButton b = new Style.IconButton(this, icon, "", Style.SURFACE);
        b.setAlpha(0.5f);
        b.setContentDescription(label);
        b.onTap(action);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET));
        p.gravity = gravity;
        p.leftMargin = p.topMargin = p.rightMargin = p.bottomMargin = dp(20);
        parent.addView(b, p);
        return b;
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
        boolean want = running && !launcherShown()
            && setupScreen.getVisibility() != View.VISIBLE && prefs.getBoolean(PREF_FULL_PAD, false);
        if (want == padFullscreen) return;
        padFullscreen = want;
        rebuildRunning();
        applyImmersive();
    }

    private void applyImmersive() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (padFullscreen || gamepadShown) {
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
        if (!launcherShown()) keyView.requestFocus();
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
        touchBtn = new Style.IconButton(this, R.drawable.xd_ic_pointer, getString(R.string.xd_mode_mouse_short), Style.SURFACE);
        touchBtn.onTap(new Runnable() {
            @Override public void run() {
                apps.toggleTouchMode();
                updateStatus();
            }
        });
        forwardBtn = new Style.IconButton(this, R.drawable.xd_ic_keys_phone, getString(R.string.xd_keys_short), Style.SURFACE);
        forwardBtn.onTap(new Runnable() {
            @Override public void run() {
                ownership.toggle();
            }
        });
        gamepadBtn = new Style.IconButton(this, R.drawable.xd_ic_gamepad, getString(R.string.xd_gamepad_short), Style.SURFACE);
        gamepadBtn.setContentDescription(getString(R.string.xd_gamepad));
        gamepadBtn.onTap(new Runnable() {
            @Override public void run() { showGamepad(); }
        });
        launcherBtn = new Style.IconButton(this, R.drawable.xd_ic_apps, getString(R.string.xd_launcher), Style.SURFACE);
        launcherBtn.onTap(new Runnable() {
            @Override public void run() { openChooserOverSession(); }
        });
        dimBtn = new Style.IconButton(this, R.drawable.xd_ic_moon, getString(R.string.xd_dim_now), Style.SURFACE);
        dimBtn.onTap(new Runnable() {
            @Override public void run() { setDimmed(true); }
        });
        settingsBtn = new Style.IconButton(this, R.drawable.xd_ic_settings, getString(R.string.xd_settings), Style.SURFACE);
        settingsBtn.onTap(new Runnable() {
            @Override public void run() { showSettings(); }
        });
    }

    /** The Launchpad mode's way back from the touchpad page to the grid: the accent pill in the top row. */
    private TextView launchpadPill() {
        final TextView b = Style.label(this, getString(R.string.xd_switcher_short), Style.TEXT, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(8), 0, dp(14), 0);
        b.setBackground(Style.ripple(this, Style.ACCENT, 18));
        Drawable back = getDrawable(R.drawable.xd_ic_back);
        if (back != null) {
            back = back.mutate();
            back.setTint(Style.TEXT);
            int s = dp(18);
            back.setBounds(0, 0, s, s);
            b.setCompoundDrawablesRelative(back, null, null, null);
            b.setCompoundDrawablePadding(dp(4));
        }
        b.setContentDescription(getString(R.string.xd_switcher_short));
        b.setOnClickListener(v -> {
            Style.tick(v);
            showLaunchpad();
        });
        return b;
    }

    private void addTopButtons(LinearLayout row, boolean fill) {
        for (View b : new View[]{audioBtn, touchBtn, forwardBtn, gamepadBtn, launcherBtn, dimBtn, settingsBtn}) {
            LinearLayout.LayoutParams p = fill ? new LinearLayout.LayoutParams(0, dp(Style.TARGET + 4), 1)
                : new LinearLayout.LayoutParams(dp(Style.TARGET + 4), dp(Style.TARGET));
            p.leftMargin = fill ? dp(2) : dp(4);
            if (fill) p.rightMargin = dp(2);
            row.addView(b, p);
        }
    }

    /**
     * 功能键面板：在触控区上面展开。竖屏两行（修饰键、方向键），横屏并成一行省高度；锁定了修饰键时再加一行组合键。
     * 面板本身跟着收起，收起时不占地方。
     */
    private View buildKeysPanel() {
        boolean landscape = isLandscape();
        int gap = dp(landscape ? 5 : 6), keyH = dp(landscape ? 38 : 46);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Style.rounded(this, Style.SURFACE, landscape ? 14 : 16));
        int edge = dp(landscape ? 7 : 8);
        panel.setPadding(edge, edge, edge, edge);
        panel.setVisibility(View.GONE);
        keysPanel = panel;

        LinearLayout first = new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        addKey(first, keyButton("Esc", KeyInputView.SCAN_ESC), 0);
        addKey(first, keyButton("Tab", KeyInputView.SCAN_TAB), gap);
        addKey(first, latchButton("Ctrl", KeyInputView.SCAN_CTRL), gap);
        addKey(first, latchButton("Alt", KeyInputView.SCAN_ALT), gap);
        addKey(first, latchButton("Super", KeyInputView.SCAN_SUPER), gap);
        addKey(first, latchButton("Shift", KeyInputView.SCAN_SHIFT), gap);
        if (landscape) {
            View spacer = new View(this);
            first.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
            addKey(first, keyButton("←", KeyInputView.SCAN_LEFT), gap);
            addKey(first, keyButton("↑", KeyInputView.SCAN_UP), gap);
            addKey(first, keyButton("↓", KeyInputView.SCAN_DOWN), gap);
            addKey(first, keyButton("→", KeyInputView.SCAN_RIGHT), gap);
            panel.addView(first, new LinearLayout.LayoutParams(-1, keyH));
        } else {
            panel.addView(first, new LinearLayout.LayoutParams(-1, keyH));
            LinearLayout arrows = new LinearLayout(this);
            arrows.setOrientation(LinearLayout.HORIZONTAL);
            addKey(arrows, keyButton("←", KeyInputView.SCAN_LEFT), 0);
            addKey(arrows, keyButton("↑", KeyInputView.SCAN_UP), gap);
            addKey(arrows, keyButton("↓", KeyInputView.SCAN_DOWN), gap);
            addKey(arrows, keyButton("→", KeyInputView.SCAN_RIGHT), gap);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, keyH);
            ap.topMargin = gap;
            panel.addView(arrows, ap);
        }
        // 系统键盘的字直接交给应用，加不上这里锁定的修饰键，所以把常用组合的字母放在这里
        LinearLayout combos = new LinearLayout(this);
        combos.setOrientation(LinearLayout.HORIZONTAL);
        comboRow = combos;
        combos.setVisibility(View.GONE);
        addKey(combos, keyButton(getString(R.string.xd_key_select_all), SCAN_A), 0);
        addKey(combos, keyButton(getString(R.string.xd_key_cut), SCAN_X), gap);
        addKey(combos, keyButton(getString(R.string.xd_key_copy), SCAN_C), gap);
        addKey(combos, keyButton(getString(R.string.xd_key_paste), SCAN_V), gap);
        addKey(combos, keyButton(getString(R.string.xd_key_undo), SCAN_Z), gap);
        LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(-1, keyH);
        cr.topMargin = gap;
        panel.addView(combos, cr);
        // KeysPanel.dc.html: what a latched Ctrl means, under the keys
        keysNote = Style.label(this, "", Style.TEXT_DIM, 11);
        keysNote.setGravity(Gravity.CENTER);
        keysNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = gap;
        panel.addView(keysNote, np);
        return panel;
    }

    private void addKey(LinearLayout row, View key, int leftGapPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.leftMargin = leftGapPx;
        row.addView(key, p);
    }

    /** 触摸板和触控面在同一个位置，看模式显示哪个。 */
    private View buildTouchArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);

        FrameLayout frame = new FrameLayout(this);
        touchpad = new TouchpadView(this, output);
        touchpad.setSpeed(prefs.getFloat(PREF_SPEED, 1f));
        touchpad.setScrollSpeed(prefs.getFloat(PREF_SCROLL_SPEED, 1f));
        wheelSpeed = prefs.getFloat(PREF_SCROLL_SPEED, 1f);
        // 自然滚动：所有设备共用一个设置（INPUT.md §12），默认开
        touchpad.setNaturalScroll(prefs.getBoolean(PREF_NATURAL_SCROLL, true));
        frame.addView(touchpad, new FrameLayout.LayoutParams(-1, -1));
        directTouch = new DirectTouchView(this, new DirectTouchView.Sink() {
            // 经 Router：切换器开着时光标和点按给它
            @Override public void moveCursor(int dx, int dy) { dockPointer.move(padIn, dx, dy); }
            @Override public void tap() { dockPointer.click(padIn, 1); }
            @Override public void press(boolean down) { dockPointer.button(padIn, 1, down); }
            @Override public void frame(int action, int[] ids, float[] x, float[] y) {
                if (client == apps.input()) apps.touchFrame(action, ids, x, y);
            }
            @Override public void action(int a, int arg) { padIn.action(a, arg); }
        });
        directTouch.setSpeed(prefs.getFloat(PREF_SPEED, 1f));
        frame.addView(directTouch, new FrameLayout.LayoutParams(-1, -1));
        // 全屏是触控区自己的事，图标放在它的角上
        Style.IconButton enter = new Style.IconButton(this, R.drawable.xd_ic_fullscreen, "", Style.SURFACE_RAISED);
        enter.setAlpha(0.7f);
        enter.setContentDescription(getString(R.string.xd_fullscreen_pad));
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
        if (keysPanel == null) return;
        boolean show = (keysExpanded || keyboardOpen) && !padFullscreen;
        keysPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        applyComboRow();
        if (keysHandle != null) {
            keysHandle.setIcon(show ? R.drawable.xd_ic_chevron_up : R.drawable.xd_ic_chevron_down);
            keysHandle.setActive(show);
        }
    }

    /** 转屏不重建 Activity（窗口和输入都不能断），只换一套布局。 */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (c.orientation == lastOrientation) return;
        lastOrientation = c.orientation;
        rebuildRunning();
        // The list and the grid each have a portrait and a landscape layout (ConsoleListLand.dc.html): built anew
        rebuildChooser();
        rebuildLaunchpad();
        applyImmersive();
    }

    /** The console list is built for the orientation it first was; a turn builds it again where it was. */
    private void rebuildChooser() {
        if (chooser == null || screens == null) return;
        int at = screens.indexOfChild(chooser);
        if (at < 0) return;
        boolean shown = chooser.getVisibility() == View.VISIBLE;
        ime.hideSoftInputFromWindow(chooser.getWindowToken(), 0);
        screens.removeView(chooser);
        chooser = new ChooserView(this, chooserHost);
        chooser.setVisibility(shown ? View.VISIBLE : View.GONE);
        screens.addView(chooser, at, new FrameLayout.LayoutParams(-1, -1));
        // The new view starts blank: what the old one was told goes to it too
        updateChooserDisplay();
        setLauncherNotice(launcherNotice, launcherNoticeAction);
        updateAudioChip();
        updateActivationPrompt();
        showOwnership();
        chooser.setStopping(session.stopping());
        if (shown) keyView.requestFocus();
    }

    /** The Launchpad grid is built for the orientation it is first shown in; a turn builds it again where it was. */
    private void rebuildLaunchpad() {
        if (launchpad == null || screens == null) return;
        int at = screens.indexOfChild(launchpad);
        if (at < 0) return;
        boolean shown = launchpad.getVisibility() == View.VISIBLE;
        screens.removeView(launchpad);
        launchpad = new LaunchpadView(this, launchpadHost);
        launchpad.setVisibility(shown ? View.VISIBLE : View.GONE);
        launchpad.setNotice(null, null);  // notices are cards (PhoneNotice), not a line
        screens.addView(launchpad, at, new FrameLayout.LayoutParams(-1, -1));
        updateChooserTop();
        updateAudioChip();
        updateStatus();
    }

    /** 键盘弹出：展开功能键。横屏高度很紧，再收起顶部一行。 */
    private void fitToKeyboard() {
        WindowInsets w = screens.getRootWindowInsets();
        // 实体键盘打字时输入法只露出一条候选栏，也算 IME 可见：那时顶部一行留着，否则收起后就一直回不来
        boolean open = w != null && w.isVisible(WindowInsets.Type.ime())
            && w.getInsets(WindowInsets.Type.ime()).bottom >= getWindow().getDecorView().getHeight() * SOFT_KEYBOARD_PART;
        if (open != keyboardOpen) {
            keyboardOpen = open;
            softKeysAsked = false;
            syncKeyMode();
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

    private LinearLayout.LayoutParams bottomParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.leftMargin = p.rightMargin = dp(3);
        return p;
    }

    private TextView smallKey(String label) {
        return Style.button(this, label, Style.SURFACE_RAISED, isLandscape() ? 12 : 13, isLandscape() ? 9 : 10);
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
        // Let go even with no target left: the release goes where the press went (the Router knows), and without it
        // the keyboard stayed bound to that target, a Mac long gone, with everything typed later going there
        if (client != null || !on) keysIn.key(l.scan, on);
        l.button.setBackground(Style.toggle(this, on, Style.SURFACE_RAISED, isLandscape() ? 9 : 10));
        l.button.setTextColor(on ? Style.ACCENT_2 : Style.TEXT);
        applyComboRow();
    }

    /** Ctrl、Alt 这些锁定时才显示。桌面的键盘文字会经过这里，用不着。 */
    private void applyComboRow() {
        if (comboRow == null) return;
        boolean show = apps.isActive() && keysPanel != null && keysPanel.getVisibility() == View.VISIBLE;
        boolean latched = false;
        StringBuilder names = new StringBuilder();
        for (Latch l : latches) {
            if (!l.on) continue;
            latched = true;
            if (names.length() > 0) names.append("、");
            names.append(l.button.getText());
        }
        if (show && latched) show = true;
        else show = false;
        comboRow.setVisibility(show ? View.VISIBLE : View.GONE);
        // KeysPanel.dc.html's note: while a modifier is latched, say what the next tap does
        if (keysNote != null) {
            boolean visible = latched && keysPanel != null && keysPanel.getVisibility() == View.VISIBLE;
            keysNote.setText(latched ? getString(R.string.xd_keys_latched, names) : "");
            keysNote.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
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
                    dockPointer.button(padIn, button, true);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    dockPointer.button(padIn, button, false);
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
    private boolean chooserTyping;
    private static final long SEARCH_FOCUS_MS = 200;

    /**
     * A finger went down on the search line of the phone's list (or of the Launchpad grid): the window is given the
     * focus it needs for the keyboard, and the line takes it.
     */
    private void beginSearchTyping() {
        if (chooserTyping) {
            // The line is still the one being typed into: only the keyboard has to come back (the Launchpad's
            // keyboard button after the IME was put away)
            ui.removeCallbacks(searchFocus);
            ui.postDelayed(searchFocus, SEARCH_FOCUS_MS);
            return;
        }
        // The list beside a mouse takes no focus (updatePhoneFocus); its search line needs it, and the keyboard
        chooserTyping = true;
        boolean wasPassive = phonePassive;
        updatePhoneFocus();
        // The touch that came to a window taking no focus did not bring the focus to the phone's display
        if (wasPassive && !phonePassive) apps.focusDisplay(Display.DEFAULT_DISPLAY);
        ui.postDelayed(searchFocus, SEARCH_FOCUS_MS);
    }

    /** After the window became focusable for the search line: the focus and the keyboard, which the touch did not get. */
    private final Runnable searchFocus = () -> {
        if (!chooserTyping) return;
        if (chooser != null && chooser.getVisibility() == View.VISIBLE) chooser.focusSearch();
        else if (launchpad != null && launchpad.getVisibility() == View.VISIBLE) launchpad.focusSearch();
    };

    private void updatePhoneFocus() {
        if (chooser == null || setupScreen == null) return;
        // The list (or the Launchpad grid) as the phone's page beside a mouse (showOpened) is worked by finger and
        // needs no focus either: with it the keyboard would type into the phone, not into the app. Until its search
        // line is touched
        boolean list = launcherShown();
        boolean listNeedsFocus = list && !(launcherIsDefault() && !chooserTyping);
        // An app session whose display was unplugged has nowhere to send the keys: the phone takes them (and focus) back
        boolean passive = (apps.isActive() && apps.hasDisplay() || (desktopTaken && session.isActive())) && !listNeedsFocus
            && setupScreen.getVisibility() != View.VISIBLE;
        if (passive == phonePassive) return;
        phonePassive = passive;
        if (passive) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            refocusExternal();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    /** 焦点放回外接屏：应用模式给应用，桌面接管时给桌面窗口。 */
    private static final long REFOCUS_AFTER_RESIZE_MS = 400;
    private final Runnable refocusAfterResize = () -> {
        if (phonePassive) refocusExternal();
    };

    private void refocusExternal() {
        if (apps.isActive()) {
            apps.focusExternal();
        } else if (desktopTaken) {
            Display d = desktopDisplay();
            if (d != null) apps.focusDisplay(d.getDisplayId());
        }
    }

    /**
     * 桌面的接管：键盘焦点给外接屏上的桌面窗口，有实体键盘时输入法的选词框也在那里。交还：都回手机，选词框在手机上，
     * 手机的触摸板照常用。要注入服务来移焦点。
     */
    private void setDesktopTaken(boolean on) {
        if (on == desktopTaken) return;
        Display d = desktopDisplay();
        if (on && (d == null || !apps.hasInjector())) {
            Toast.makeText(this, R.string.xd_take_over_needs_injector, Toast.LENGTH_LONG).show();
            return;
        }
        desktopTaken = on;
        // 手机窗口没了焦点就抓不住鼠标，这时由外接屏上的桌面窗口来抓
        MainActivity.setTakeMouse(on);
        if (d != null) apps.setImeOnDisplay(d.getDisplayId(), on && ImePlacement.hasPhysicalKeyboard());
        updatePhoneFocus();
        if (!on) {
            apps.focusDisplay(Display.DEFAULT_DISPLAY);
            if (keyView != null) keyView.requestFocus();
        }
        updateCapture();
        updateStatus();
    }

    /**
     * 外接屏上有内容时才有，车机没有鼠标。Over Android apps Android's own pointer opens it; over X and a Mac the mouse
     * is captured for the content and DockPointer opens it. Not over the X desktop taken over the old way (its window
     * has the mouse itself), and the home (the Launchpad as what the screen shows) needs none.
     */
    private void updateExternalDock() {
        if (externalDock == null) return;
        // An Android app in front has the screen whether or not a Termux desktop runs behind it (applyModeToTopBar
        // ends the old takeover when an app comes)
        boolean android = apps.isActive();
        boolean content = !apps.isActive() && (session.isActive() || contentKey != null) && !desktopTaken && !SwitcherOverlay.isHome();
        Display d = (android || content) && prefs.getBoolean(PREF_EXTERNAL_DOCK, true) && Settings.canDrawOverlays(this) ? externalDisplay() : null;
        externalDock.setDisplay(d != null && !TargetDisplay.isCar(d) ? d : null);
        // The notification centre is over every content, the home included (NOTIFY.md §1), on the same display —
        // only with the notification access granted: without it everything is as before (NOTIFY.md §2)
        Display nd = Settings.canDrawOverlays(this) && NotifyCenter.accessGranted(this) ? externalDisplay() : null;
        if (notify != null) notify.setDisplay(nd != null && !TargetDisplay.isCar(nd) ? nd : null);
        // Without the access the centre is not there at all: said once, when an external screen is first used
        if (nd == null && Settings.canDrawOverlays(this) && !NotifyCenter.accessGranted(this)) offerNotifyAccess();
        // The Mac menu (MACMENU.md §2): only over a single Mac window or app — never the Mac's whole desktop,
        // whose own menu bar is in the picture. Its display follows the centre's (no permission gates of its own)
        boolean macContent = contentKey != null && contentKey.startsWith(MacSource.PREFIX)
                && (contentKey.indexOf(":win:") >= 0 || contentKey.indexOf(":app:") >= 0);
        if (menus != null) {
            Display md = Settings.canDrawOverlays(this) ? externalDisplay() : null;
            menus.setDisplay(md != null && !TargetDisplay.isCar(md) ? md : null);
            menus.update(macContent ? macOnScreen() : null);
        }
        // Open over something that is no longer the target: it goes, and the pointer is the target's again
        dockPointer.check();
    }

    private void toggleKeyboard() {
        WindowInsets w = getWindow().getDecorView().getRootWindowInsets();
        if (w != null && w.isVisible(WindowInsets.Type.ime())) {
            softKeysAsked = false;
            ime.hideSoftInputFromWindow(keyView.getWindowToken(), 0);
        } else {
            // The soft keyboard types through the phone's input method, for a Mac too: a text connection first
            softKeysAsked = true;
            syncKeyMode();
            keyView.requestFocus();
            ime.showSoftInput(keyView, 0);
        }
        syncKeyMode();
    }

    // --- 状态 ---

    private void setStatus(int color, String s) {
        Log.i(TAG, s);
        setStatusAction(null);
        // The Launchpad grid's top line is the same line; there it stays while the page does (it is the home)
        if (launchpad != null) launchpad.setStatus(color, s);
        statusText.setText(s);
        // The line the mockups always draw (Main, ConsoleLand, KeysPanel, TouchMode), warnings and errors included:
        // only its absence is hidden, as the Launchpad grid's copy does. The dot keeps the state's colour.
        int v = s == null || s.isEmpty() ? View.GONE : View.VISIBLE;
        statusText.setVisibility(v);
        statusDot.setVisibility(v);
        if (statusRow != null) statusRow.setVisibility(v);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        statusDot.setBackground(d);
    }

    /** The status line as a way in (the injector-loss page), or only words again. */
    private void setStatusAction(Runnable action) {
        if (statusText != null) {
            statusText.setClickable(action != null);
            statusText.setOnClickListener(action == null ? null : v -> {
                Style.tick(v);
                action.run();
            });
        }
        if (launchpad != null) launchpad.setStatusAction(action);
    }

    /** 接管在两种模式下都有：应用模式抓实体键鼠，桌面模式把键盘焦点和输入法放到外接屏上。 */
    private void applyModeToTopBar() {
        boolean app = apps.isActive();
        updateExternalDock();
        // 应用接手了外接屏：桌面的接管不再有意义，输入法也放回去
        if (desktopTaken && app) takeDesktopWhenBack = true;
        if (desktopTaken && (app || !session.isActive())) setDesktopTaken(false);
        updatePhoneFocus();
        applyKeysButton();
        // In the Launchpad mode the grid's own pill is the way to it (LaunchpadTouchpad*.dc.html)
        launcherBtn.setVisibility(launchpadMode() ? View.GONE : View.VISIBLE);
        backBtn.setVisibility(app ? View.VISIBLE : View.GONE);
        // 安卓应用要键盘时输入法自己会出来，也有自己的收起键。这个按钮只有桌面要用
        keyboardBtn.setVisibility(app ? View.GONE : View.VISIBLE);
        touchBtn.setVisibility(apps.canUseTouchMode() ? View.VISIBLE : View.GONE);
        touchBtn.setActive(false);
        applyFullscreenCorners();
        // GAMEPAD.md: only for an Android app on the external display; the app it is for picks the layout
        gamepadBtn.setVisibility(app ? View.VISIBLE : View.GONE);
        if (gamepadShown && !app) hideGamepad();
        if (gamepadShown) {
            AppCatalog.App a = apps.app();
            gamepadView.setPackage(a != null ? a.packageName : null);
        }
        applyInputMode();
    }

    /**
     * 键鼠：触摸板加左右键。触控：手指直接到外接屏上，这时不用鼠标键，触控区更大。实体鼠标在两种模式下都能用，
     * 触控模式下左键是一根手指，右键是两根，滚轮是滑动。
     */
    private void applyInputMode() {
        // INPUT.md §4 through the Router: the phone's screen is a blind surface, a tablet receiver a looked-at one
        boolean touch = router.likeTablet(Router.BLIND);
        if (wireless != null) wireless.setTouchMode(router.likeTablet(Router.LOOK));
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

    /**
     * The keys button (INPUT.md §7), only with a physical keyboard or mouse on the phone: without one there is nothing
     * for it to give. Where they are is in its icon and colour: blue, the mouse joined to the screen, on the display.
     */
    private void applyKeysButton() {
        if (forwardBtn == null) return;
        boolean running = apps.isActive() || session.isActive() || contentKey != null || SplitStageActivity.isActive();
        forwardBtn.setVisibility(running && hasExternalInput() ? View.VISIBLE : View.GONE);
        showOwnership();
    }

    /** INPUT.md §7: one indicator for every kind of session, 键鼠在外屏 / 键鼠在本机. Tapping it switches. */
    private void showOwnership() {
        boolean on = ownership.forwarded();
        String said = getString(on ? R.string.xd_keys_external : R.string.xd_keys_phone);
        forwardBtn.setLabel(getString(on ? R.string.xd_keys_display_short : R.string.xd_keys_phone_short));
        forwardBtn.setIcon(on ? R.drawable.xd_ic_keys_external : R.drawable.xd_ic_keys_phone);
        forwardBtn.setContentDescription(said);
        forwardBtn.setActive(on);
        // The list's top row and the Launchpad grid's bottom bar have the same button, there while this one is
        // (applyKeysButton)
        boolean shown = (apps.isActive() || session.isActive() || contentKey != null) && hasExternalInput();
        if (chooser != null) chooser.setKeys(shown ? said : null, on);
        if (launchpad != null) launchpad.setKeys(shown ? said : null, on);
    }

    private void updateAppStatus() {
        showOwnership();
        boolean touch = apps.isTouchMode();
        // A mode button names the mode it is in, and never takes the on state's colour
        touchBtn.setLabel(getString(touch ? R.string.xd_mode_touch : R.string.xd_mode_mouse_short));
        touchBtn.setIcon(touch ? R.drawable.xd_ic_touch : R.drawable.xd_ic_pointer);
        if (apps.hasInjector() && apps.hasDisplay() && apps.app() == null) {
            setStatus(Style.WARN, getString(R.string.xd_status_no_app));
        } else if (!apps.hasInjector()) {
            setStatus(Style.DANGER, getString(apps.usesShell() ? R.string.xd_status_injector_gone_shell : R.string.xd_status_injector_gone_shizuku));
            // ConnectFail.dc.html: the red line opens the way to fix it, then comes back to the touchpad
            setStatusAction(this::showInjectorLoss);
        } else if (!apps.hasDisplay()) {
            setStatus(Style.WARN, getString(R.string.xd_status_display_gone_app, apps.label()));
        } else if (wireless.reconnecting()) {
            setStatus(Style.WARN, getString(R.string.xd_status_reconnecting, wireless.inputSource()));
        } else if (apps.isGameMode()) {
            // GAMING.md: the app has the real keyboard and mouse; how to come back
            setStatus(Style.OK, getString(R.string.xd_status_game, apps.label()));
        } else if (apps.isForwarding() && wireless.inputSource() != null) {
            setStatus(Style.OK, getString(R.string.xd_status_input_computer, apps.label(), wireless.inputSource()));
        } else if (apps.isForwarding() && apps.deviceCount() == 0) {
            setStatus(Style.WARN, getString(R.string.xd_status_no_devices, apps.label()));
        } else if (apps.isForwarding()) {
            setStatus(Style.OK, getString(R.string.xd_status_input_display, apps.label()));
        } else {
            setStatus(Style.OK, getString(R.string.xd_status_input_phone, apps.label()));
        }
    }

    private void updateStatus() {
        updatePermissionBadges();
        if (wireless != null) {
            wireless.setDesktopShown(session.isConnected() && !apps.isActive());
            // The desktop content's cursor is in the picture: a Mac, the switchboard's arrow, the driven Dock's
            // arrow (over X without these the X cursor may be hidden by an absolute placement: INPUT_MODE 1)
            // (WirelessDisplay adds the switchboard and the Dock itself: not while a receiver shows its own pointer)
            wireless.setDesktopCursor(contentKey != null && contentKey.startsWith(MacSource.PREFIX));
            // An Android receiver hides its own pointer when the picture has a cursor: our arrow stays for it
            apps.setRemoteHidesPointer(wireless.receiverHidesPointer());
        }
        audioRoute.setCar(carConnected());
        applyFullscreen();
        applyModeToTopBar();
        if (apps.isActive()) {
            updateAppStatus();
            return;
        }
        // The split-stage prototype has the screen (DISPLAY.md §15): the phone's input goes to its own injector session
        if (SplitStageActivity.isActive()) {
            if (client != SplitStageActivity.input) setTarget(SplitStageActivity.input);
            setStatus(Style.OK, getString(R.string.xd_split_running));
            return;
        }
        if (contentKey != null && !desktopTaken && (session.isConnected() || !contentKey.startsWith(LinuxSource.PREFIX))) {
            // A content has the screen: say what it is. Without this a Mac read "Desktop not connected", which is
            // about the X desktop, and a Linux program read as its session's name
            ContentSource.Entry e = Contents.find(contentKey);
            String label = e != null ? e.label : "";
            Devices dv = Devices.get(this);
            Devices.Device dev = dv.find(dv.deviceOf(contentKey));
            if (dev != null && dev.type != Devices.Type.LINUX && dev.state != Devices.State.CONNECTED)
                setStatus(Style.WARN, dev.name + " · " + getString(Devices.stateLabel(dev.state)) + (dev.note != null && !dev.note.isEmpty() ? " · " + dev.note : ""));
            else setStatus(Style.OK, getString(onCar() ? R.string.xd_status_desktop_car : R.string.xd_status_desktop_display, label));
            return;
        }
        // Nothing runs: the touchpad page then works the switchboard on the external screen, and there is nothing to warn of
        if (!session.isActive() && contentKey == null) {
            setStatus(Style.OK, "");
            return;
        }
        // The X shown may be the built-in Linux's while Termux's desktop runs on behind it: desktopName is Termux's
        // (its xdesk reports it), so the built-in one is named by what is shown (owner, 23 November)
        String shownName = XServers.isBuiltin(XServers.shown()) ? getString(R.string.xd_bl_title) : desktopName;
        if (session.isConnected() && desktopTaken) setStatus(Style.OK, getString(R.string.xd_status_desktop_taken, shownName != null ? shownName : ""));
        else if (session.isConnected()) setStatus(Style.OK, shownName != null ? getString(onCar() ? R.string.xd_status_desktop_car : R.string.xd_status_desktop_display, shownName) : getString(R.string.xd_status_desktop_connected));
        else setStatus(Style.WARN, getString(R.string.xd_status_desktop_not_connected));
    }

    private void showSetup(String title, String message, boolean progress, String action, View.OnClickListener onAction) {
        chooser.setVisibility(View.GONE);
        if (launchpad != null) launchpad.setVisibility(View.GONE);
        setLauncherNotice(null);
        displayNoticeShown = false;
        setupScreen.setVisibility(View.VISIBLE);
        applyFullscreen();
        setupBrand.setVisibility(View.VISIBLE);
        setupKey.setVisibility(View.GONE);
        setupIconBox.setVisibility(View.GONE);
        setupSpinTop.setVisibility(View.GONE);
        setupCode.setVisibility(View.GONE);
        setupNote2.setVisibility(View.GONE);
        setupTitle.setTextColor(Style.TEXT);
        setupTitle.setTextSize(20);
        setupMessage.setTextColor(Style.TEXT_DIM);
        setupMessage.setTextSize(14);
        setupAction.setBackground(Style.ripple(this, Style.ACCENT, 12));
        setupAction.setTextColor(Style.TEXT);
        setupAction.setLayoutParams(stretchable(dp(44), dp(24), -2));
        setupSecond.setLayoutParams(stretchable(dp(44), dp(12), -2));
        setupBack.setText(getString(R.string.xd_back_to_launcher));
        setupBack.setTextColor(Style.TEXT);
        setupBack.setBackground(Style.ripple(this, Style.SURFACE_RAISED, 12));
        setupBack.setLayoutParams(stretchable(dp(44), dp(12), -2));
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

    /** The setup buttons' layout: the mockups' pages draw them full width, the generic ones only as wide as their words. */
    private LinearLayout.LayoutParams stretchable(int height, int top, int width) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = top;
        return p;
    }

    /** Waiting and ConnectFail: full-width buttons, as those pages are drawn. */
    private void stretchSetup() {
        setupAction.setLayoutParams(stretchable(dp(44), dp(24), -1));
        if (setupSecond.getVisibility() == View.VISIBLE)
            setupSecond.setLayoutParams(stretchable(dp(44), dp(12), -1));
        setupBack.setLayoutParams(stretchable(dp(44), dp(12), -1));
    }

    /** The waiting page's command box and the line under it (Waiting.dc.html). */
    private void setupExtras(String command, String note) {
        setupCode.setText(command);
        setupCode.setVisibility(View.VISIBLE);
        setupNote2.setText(note);
        setupNote2.setVisibility(View.VISIBLE);
        setupBrand.setVisibility(View.GONE);
    }

    /**
     * The setup page asking for a permission, in PermitCard's look: the amber key, "Turn on «what»", why and where,
     * and the blue primary button (the usual word when {@code action} is null), as PermSheet draws it.
     */
    private void showPermitSetup(String what, String why, String path, String action, View.OnClickListener onAction) {
        showSetup(getString(R.string.xd_permit_title, what), path != null ? why + "\n\n" + path : why, false,
            action != null ? action : getString(R.string.xd_permit_open), onAction);
        setupKey.setVisibility(View.VISIBLE);
        setupAction.setBackground(Style.ripple(this, Style.ACCENT, 12));
        setupAction.setTextColor(Style.TEXT);
    }

    /** The system's page for "display over other apps", for XDesk. */
    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            Log.w(TAG, "no overlay settings", e);
        }
    }

    /** Back from the details page with the Termux command permission: what waited for it goes on. */
    private void resumeRunCommand() {
        int what = awaitingRunCommand;
        if (what == AWAIT_NONE || checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) return;
        awaitingRunCommand = AWAIT_NONE;
        if (what == AWAIT_DESKTOP && setupScreen.getVisibility() == View.VISIBLE) launchDesktop(pendingSession, pendingReplace);
        else if (what == AWAIT_LIST && setupScreen.getVisibility() == View.VISIBLE) {
            showChooser();
            refreshTermux();
        } else if (what == AWAIT_STOP) confirmStop();
    }

    /** The app's details page in the system's settings (its permissions, "allow restricted settings"). */
    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            Log.w(TAG, "no app details page", e);
        }
    }

    /** 紧接着 showSetup 调用：多一个次要的选择。 */
    private void addSetupChoice(String action, View.OnClickListener onAction) {
        setupSecond.setText(action);
        setupSecond.setOnClickListener(onAction);
        setupSecond.setVisibility(View.VISIBLE);
    }

    private void showRunning() {
        chooserTyping = false;
        ui.removeCallbacks(searchFocus);
        if (chooser.getVisibility() == View.VISIBLE) {
            ime.hideSoftInputFromWindow(chooser.getWindowToken(), 0);
            chooser.clearSearch();
        }
        if (launchpad != null && launchpad.getVisibility() == View.VISIBLE) {
            ime.hideSoftInputFromWindow(launchpad.getWindowToken(), 0);
            launchpad.clearSearch();
        }
        // The touchpad page is the phone's page now (in the Launchpad mode too: the grid is one tap away)
        launchpadPage = false;
        chooser.setVisibility(View.GONE);
        if (launchpad != null) launchpad.setVisibility(View.GONE);
        setLauncherNotice(null);
        displayNoticeShown = false;
        setupScreen.setVisibility(View.GONE);
        applyFullscreen();
        refreshDocks();
        keyView.requestFocus();
        updateCapture();
    }

    private void updateDiag() {
        if (diagText == null || keyView == null || diagText.getVisibility() != View.VISIBLE) return;
        diagText.setText(getString(R.string.xd_diag, yn(hasWindowFocus()), yn(keyView.isFocused()), yn(hasExternalMouse()),
            yn(captured), keyEvents, mouseEvents));
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
        int action = e.getActionMasked();
        // The touch that wakes the screen is not for whatever is under the finger: swallow the whole gesture, every
        // pointer, until it lifts. The same holds for the gamepad and every button.
        if (dimTouchSwallowed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dimTouchSwallowed = false;
            return true;
        }
        if (dimmed && action == MotionEvent.ACTION_DOWN) {
            bumpIdle();
            dimTouchSwallowed = true;
            return true;
        }
        bumpIdle();
        if (action == MotionEvent.ACTION_DOWN) {
            // The list's search was typed into and is not any more (its keyboard is gone): the list is worked by
            // finger alone again, and the keyboard is the external app's (updatePhoneFocus)
            if (chooserTyping && !phoneTyping()) {
                chooserTyping = false;
                updatePhoneFocus();
            }
            lastFingerDown = android.os.SystemClock.uptimeMillis();
            // Not for the gamepad: it would leave game passthrough, and a pad's presses are not the phone wanting focus
            if (apps.isActive() && !gamepadShown) apps.phoneTouched();
            // 摸手机屏会把焦点拿到手机上，设置页这类窗口关掉后焦点也留在手机屏
            if (phonePassive) refocusExternal();
        }
        return super.dispatchTouchEvent(e);
    }

    /** dim_idle was a switch; dim_after_s took its place once, keeping an explicit off as 0. */
    private void migrateDimPref() {
        if (!prefs.contains(PREF_DIM_OLD)) return;
        int seconds = prefs.getBoolean(PREF_DIM_OLD, true) ? DIM_DEFAULT_SECONDS : 0;
        prefs.edit().putInt(PREF_DIM, seconds).remove(PREF_DIM_OLD).apply();
    }

    /** Auto-dim only beside another screen: a cable/car display, or the wireless/web display (a Mac receiver is one). */
    private boolean externalScreenForDim() {
        return externalDisplay() != null || (wireless != null && wireless.state() == WirelessDisplay.STREAMING);
    }

    private static int dimChoiceIndex(int seconds) {
        for (int i = 0; i < DIM_SECONDS.length; i++) if (DIM_SECONDS[i] == seconds) return i;
        return 2;  // the 1 minute default
    }

    private void bumpIdle() {
        ui.removeCallbacks(dim);
        if (dimmed) setDimmed(false);
        int seconds = prefs.getInt(PREF_DIM, DIM_DEFAULT_SECONDS);
        // Not while another app is in front: the window would stay dim when the user comes back. With no external
        // screen there is nothing to look at on the display, so the phone never dims by itself.
        if (resumed && seconds > 0 && externalScreenForDim()) ui.postDelayed(dim, seconds * 1000L);
    }

    private void setDimmed(boolean d) {
        if (d) ui.removeCallbacks(dim);
        dimmed = d;
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.screenBrightness = d ? DIM_BRIGHTNESS : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(p);
        // Dimmed.dc.html's hint: a few seconds after dimming then gone (still dimmed); it moves every minute so
        // nothing burns in, and says its piece again after each move
        ui.removeCallbacks(dimMove);
        ui.removeCallbacks(dimHintHide);
        if (dimHint != null) {
            if (d) {
                dimHint.setTranslationX(0);
                dimHint.setTranslationY(0);
                showDimHint();
                ui.postDelayed(dimMove, DIM_MOVE_MS);
            } else {
                dimHint.setVisibility(View.GONE);
            }
        }
    }

    // --- 设置 ---

    private void updateAudioChip() {
        String label = audioRoute.shortLabel();
        if (audioBtn != null) audioBtn.setLabel(label);
        if (settingsOutputValue != null) settingsOutputValue.setText(label);
        if (chooser != null) chooser.setOutputLabel(label);
        if (launchpad != null) launchpad.setOutputLabel(label);
    }

    /** SetSound: one radio per output, each with what it is; read on every open, and again when the outputs change. */
    private void chooseOutput() {
        if (audioRoute.isCar()) {
            Toast.makeText(this, R.string.xd_output_car, Toast.LENGTH_LONG).show();
            return;
        }
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] page = new Dialog[1];
        // One setting for a cable, a wireless and a web display: system default follows the display, see AudioRoute
        final boolean receiver = wireless.isActive();
        final List<AudioRoute.Output> outputs = audioRoute.outputs();
        final String selected = audioRoute.selectedKey();
        int checked = selected.isEmpty() ? 0 : -1;
        int selectedType = selected.isEmpty() ? -1 : Integer.parseInt(selected.substring(0, selected.indexOf('|')));
        for (int i = 0; i < outputs.size(); i++) {
            AudioRoute.Output o = outputs.get(i);
            if (o.key.equals(selected) || (checked < 0 && o.type == selectedType)) checked = i + 1;
        }
        LinearLayout card = s.card(c);
        // System default's line says what it follows now (SetSound: "现在：外接屏（HDMI）")
        s.radioRow(card, getString(R.string.xd_output_system),
            receiver ? getString(R.string.xd_output_system_receiver, wireless.receiverName()) : null,
            checked == 0, () -> {
                page[0].dismiss();
                audioRoute.select(AudioRoute.KEY_SYSTEM);
            });
        for (int i = 0; i < outputs.size(); i++) {
            final AudioRoute.Output o = outputs.get(i);
            // The label is "the kind: its product name"; the mockup puts the kind first and the product under it.
            // The colon is the language's: ASCII in English, full-width in Chinese
            int ascii = o.label.indexOf(": "), wide = o.label.indexOf("：");
            int cut = ascii < 0 ? wide : wide < 0 ? ascii : Math.min(ascii, wide);
            String title = cut > 0 ? o.label.substring(0, cut) : o.label;
            String product = cut > 0 ? o.label.substring(cut + (cut == ascii ? 2 : 1)) : null;
            s.radioRow(card, title, product, checked == i + 1, () -> {
                page[0].dismiss();
                audioRoute.select(o.key);
                if (audioRoute.isMissing()) setStatus(Style.WARN, getString(R.string.xd_output_missing));
            });
        }
        TextView foot = Style.label(this, getString(R.string.xd_output_foot_note), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        foot.setPadding(dp(6), dp(8), dp(6), dp(2));
        c.addView(foot);
        page[0] = settingsPage(getString(R.string.xd_output_title), c, null);
        outputDialog = page[0];
    }

    private String[] strings(int... ids) {
        String[] s = new String[ids.length];
        for (int i = 0; i < ids.length; i++) s[i] = getString(ids[i]);
        return s;
    }

    private SettingsUi ui() {
        return new SettingsUi(this);
    }

    /** 只影响 XDesk 的触控界面：手机系统锁竖屏时这里也能横过来。默认跟随系统。 */
    private void applyOrientation() {
        // A gamepad is held sideways
        setRequestedOrientation(gamepadShown ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : ORIENTATIONS[prefs.getInt(PREF_ORIENTATION, ORIENTATION_DEFAULT)]);
    }

    // --- 手柄（GAMEPAD.md）：手机屏整个变成游戏手柄，游戏看到的是一个真手柄 ---

    private final GamepadView.Host gamepadHost = new GamepadView.Host() {
        @Override public void state(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) {
            apps.gamepad(buttons, hat, lx, ly, rx, ry, lt, rt);
        }
        @Override public void end() {
            apps.gamepadEnd();
        }
        @Override public void exit() {
            hideGamepad();
        }
    };

    private void showGamepad() {
        if (gamepadShown || !apps.isActive()) return;
        if (gamepadView == null) gamepadView = new GamepadView(this, gamepadHost);
        AppCatalog.App a = apps.app();
        gamepadView.setPackage(a != null ? a.packageName : null);
        if (gamepadView.getParent() == null) screens.addView(gamepadView, new FrameLayout.LayoutParams(-1, -1));
        gamepadShown = true;
        apps.gamepadOpen();
        // The window must stay one that touches do not take focus to (GAMEPAD.md §3); the keys go to the game
        updatePhoneFocus();
        if (phonePassive) refocusExternal();
        applyOrientation();
        applyImmersive();
    }

    private void hideGamepad() {
        if (!gamepadShown) return;
        gamepadShown = false;
        gamepadView.releaseAll();
        apps.gamepadEnd();
        screens.removeView(gamepadView);
        applyOrientation();
        applyImmersive();
    }

    private int resolutionMode() {
        int m = prefs.getInt(PREF_RESOLUTION, -1);
        if (m >= RES_AUTO && m <= RES_720) return m;
        // Carried over: never chosen is auto (the old default 1920 blurred a 1440p screen); a width chosen is its height
        if (!prefs.contains(PREF_WIDTH)) return RES_AUTO;
        int w = prefs.getInt(PREF_WIDTH, 1920);
        return w == 0 ? RES_NATIVE : w <= 1280 ? RES_720 : RES_1080;
    }

    /** "Auto · 2560×1440": a mode's label, with the size it gives on d when there is one. */
    private String resolutionLabel(int mode, Display d) {
        String label = getString(RES_LABELS[mode]);
        if (d == null) return label;
        Point p = desktopSize(d, mode);
        return getString(R.string.xd_res_with_size, label, p.x, p.y);
    }

    private void chooseResolution(final TextView value) {
        final Display d = externalDisplay();
        // 1080p and 720p only where they are less than the screen has
        final java.util.List<Integer> modes = new java.util.ArrayList<>();
        Point full = d != null ? desktopSize(d, RES_NATIVE) : null;
        for (int m = RES_AUTO; m <= RES_720; m++) {
            if (m >= RES_1080 && full != null && desktopSize(d, m).y >= full.y) continue;
            modes.add(m);
        }
        String[] labels = new String[modes.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = resolutionLabel(modes.get(i), d);
        ui().choice(getString(R.string.xd_resolution), null, labels, Math.max(0, modes.indexOf(resolutionMode())), which -> {
            prefs.edit().putInt(PREF_RESOLUTION, modes.get(which)).apply();
            if (d != null) applySize(d);
            value.setText(resolutionLabel(modes.get(which), d));
        });
    }

    /** 外接屏界面大小的默认值。应用可以在长按卡片里有自己的值。 */
    /** @param dpi -1 back to DESKTOP_DENSITY */
    private void setDesktopDensity(int dpi) {
        if (dpi < 0) prefs.edit().remove(PREF_DESKTOP_DENSITY).apply();
        else prefs.edit().putInt(PREF_DESKTOP_DENSITY, dpi).apply();
        Display d = desktopDisplay();
        if (session.isActive() && d != null && !TargetDisplay.isCar(d)) apps.setDesktopDensity(d, prefs.getInt(PREF_DESKTOP_DENSITY, DESKTOP_DENSITY));
    }

    private void addDensitySetting(SettingsUi s, LinearLayout card) {
        if (session.isActive() && !apps.isActive() && apps.hasInjector()) {
            // On the car the display's density is Android Auto's own: this setting is not applied there (setDesktopDensity)
            if (TargetDisplay.isCar(externalDisplay())) {
                s.infoRow(card, getString(R.string.xd_density), getString(R.string.xd_density_car), null);
                return;
            }
            s.numberRow(card, getString(R.string.xd_density), getString(R.string.xd_density_desktop_range, DENSITY_MIN, DENSITY_MAX, DESKTOP_DENSITY),
                "dpi", prefs.getInt(PREF_DESKTOP_DENSITY, DESKTOP_DENSITY), DENSITY_MIN, DENSITY_MAX,
                dpi -> setDesktopDensity(dpi), getString(R.string.xd_reset), () -> setDesktopDensity(-1));
            return;
        }
        if (!apps.canSetDensity()) {
            String why = getString(session.isActive() ? R.string.xd_density_desktop
                : !apps.injectorReady() ? R.string.xd_density_need_injector : R.string.xd_density_need_app);
            s.infoRow(card, getString(R.string.xd_density), why, null);
            return;
        }
        String own = apps.defaultDensity() > 0 ? String.valueOf(apps.defaultDensity()) : getString(R.string.xd_unknown);
        s.numberRow(card, getString(R.string.xd_density), getString(R.string.xd_density_range, DENSITY_MIN, DENSITY_MAX, own),
            apps.defaultDensity() > 0 ? own : "dpi", apps.savedDensity(), DENSITY_MIN, DENSITY_MAX,
            dpi -> apps.setDensity(dpi), getString(R.string.xd_reset), () -> apps.setDensity(0));
    }

    private final java.util.ArrayList<Dialog> settingsPages = new java.util.ArrayList<>();
    private Runnable settingsRefresh;
    /** The permission centre, the page it opened, and the first-start checklist; rebuilt when XDesk comes back. */
    private Dialog permCenter, permDetail, permOnboard;
    private Permissions.Item permDetailItem;
    /** Lost required permissions not yet fixed: the orange bar at the top of the phone screen. */
    private final java.util.ArrayList<Permissions.Item> permAlerts = new java.util.ArrayList<>();
    private View permBanner;
    /** The checklist is being rebuilt: its dismissal must not count as leaving it. */
    private boolean permRebuilding;

    /** A page of the settings, over the one it was opened from. Back goes down one page at a time. */
    private Dialog settingsPage(String title, View content, Runnable closed) {
        Dialog d = ui().page(title, content);
        settingsPages.add(d);
        d.setOnDismissListener(x -> {
            settingsPages.remove(d);
            if (closed != null) closed.run();
        });
        return d;
    }

    private void pageNote(LinearLayout c, int text) {
        TextView note = Style.label(this, getString(text), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        note.setPadding(dp(4), dp(4), dp(4), dp(10));
        c.addView(note, 0);
    }

    private void closeSettings() {
        for (Dialog d : new java.util.ArrayList<>(settingsPages)) d.dismiss();
        // Device pages and picture-and-sound pages are opened with SettingsUi.page by other classes: they go too
        SettingsUi.closePages();
    }

    private void showSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        addPermissionsEntry(c);
        LinearLayout device = s.card(c);
        s.linkRow(device, getString(R.string.xd_section_phone), null,
            getString(launchpadMode() ? R.string.xd_phone_ui_launchpad : R.string.xd_phone_ui_console), this::showPhoneSettings);
        s.linkRow(device, getString(R.string.xd_section_input), null, "", this::showInputSettings);
        // One sound output is not a page of its own: the row opens the output choice straight
        settingsOutputValue = s.linkRow(device, getString(R.string.xd_section_sound), null, audioRoute.shortLabel(), this::chooseOutput);
        s.linkRow(device, getString(R.string.xd_section_display), null, "", this::showDisplaySettings);
        // The notifications on the external screen here too, not only under 外屏: the access was hard to find (owner, 2026-11-15)
        boolean notifyAccess = NotifyCenter.accessGranted(this);
        s.linkRow(device, getString(R.string.xd_section_notifications), null,
            notifyAccess ? getString(R.string.xd_notify_access_on) : getString(R.string.xd_notify_access_short_off),
            notifyAccess ? null : Permissions.need(this, Permissions.NOTIFY_ACCESS),
            () -> showPermissionDetail(Permissions.NOTIFY_ACCESS), this::showNotifySettings);
        LinearLayout connect = s.section(c, getString(R.string.xd_section_connect));
        // Every external device (Linux environments and Macs) behind one entry: DEVICES.md §5
        java.util.List<Devices.Device> external = Devices.get(this).external();
        StringBuilder names = new StringBuilder();
        for (Devices.Device d : external) {
            if (names.length() > 0) names.append("、");
            names.append(d.name);
        }
        int connectedDevices = Devices.get(this).connectedCount();
        s.linkRow(connect, getString(R.string.xd_dev_connect_devices),
            external.isEmpty() ? getString(R.string.xd_dev_connect_devices_note) : names.toString(),
            connectedDevices > 0 ? getResources().getQuantityString(R.plurals.xd_dev_connected_count, connectedDevices, connectedDevices) : "",
            () -> DeviceSettings.open(this));
        final TextView[] receiver = new TextView[1], browser = new TextView[1];
        receiver[0] = s.linkRow(connect, getString(R.string.xd_wireless), getString(R.string.xd_wireless_note),
            wireless.isActive() ? wireless.receiverName() : getString(R.string.xd_wireless_off), this::showWirelessPicker);
        browser[0] = s.linkRow(connect, getString(R.string.xd_web), getString(R.string.xd_web_row_note), webValue(), this::showWebPage);
        settingsRefresh = () -> {
            receiver[0].setText(wireless.isActive() ? wireless.receiverName() : getString(R.string.xd_wireless_off));
            browser[0].setText(webValue());
        };
        // Terminal-side settings for the Linux desktop and About, in a section of their own
        LinearLayout other = s.section(c, getString(R.string.xd_section_other));
        s.linkRow(other, getString(R.string.xd_x11_settings), getString(R.string.xd_x11_settings_row_note), "",
            this::showX11Settings);
        s.linkRow(other, getString(R.string.xd_section_about), null, versionName(), this::showAboutSettings);

        if (session.isActive() || DesktopService.isRunning()) {
            LinearLayout ends = s.card(c);
            ((LinearLayout.LayoutParams) ends.getLayoutParams()).topMargin = dp(12);
            if (session.isActive()) {
                // Orange: it stops the desktop, it does not remove anything. The one on the screen: the built-in
                // Linux's is stopped by the app, not through Termux (it asked for Termux's permission and stopped
                // Termux's desktop, 23 November)
                boolean builtin = XServers.isBuiltin(XServers.shown());
                s.stopRowIn(ends, getString(builtin ? R.string.xd_bl_stop_row : R.string.xd_end_desktop_row),
                    getString(builtin ? R.string.xd_bl_stop_note : R.string.xd_end_desktop_row_note), () -> {
                    closeSettings();
                    if (builtin) deviceActions.stopBuiltin();
                    else confirmStop();
                });
            }
            if (DesktopService.isRunning()) {
                s.dangerRowIn(ends, getString(R.string.xd_end_xdesk), getString(R.string.xd_end_xdesk_note), () -> {
                    closeSettings();
                    confirmEndXDesk();
                });
            }
        }
        settingsPage(getString(R.string.xd_settings), c, () -> {
            settingsRefresh = null;
            settingsOutputValue = null;
        });
    }

    // --- 权限：设置首页的一项、权限中心、每项的说明页、首次启动清单、失效提醒 ---

    /** Settings home, at the top: one card that says whether the required and recommended permissions are all there. */
    private void addPermissionsEntry(LinearLayout c) {
        int need = Permissions.attention(this);
        boolean ok = need == 0;
        int tint = (Style.WARN & 0x00FFFFFF) | (ok ? 0 : 0x1A000000);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        GradientDrawable bg = Style.rounded(this, ok ? Style.SURFACE : tint, 16);
        if (!ok) bg.setStroke(dp(1), (Style.WARN & 0x00FFFFFF) | 0x4D000000);
        row.setBackground(bg);
        row.setClickable(true);
        row.setFocusable(false);
        LinearLayout tile = new LinearLayout(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Style.rounded(this, ok ? 0x1F3FCF7F : Style.WARN, 9));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.xd_ic_shield);
        icon.setColorFilter(ok ? Style.OK : 0xFFFFFFFF);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.addView(tile, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        TextView title = Style.label(this, getString(R.string.xd_perm_title), Style.TEXT, Style.TEXT_BODY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);
        TextView sub = Style.label(this, ok ? getString(R.string.xd_perm_home_ok) : getString(R.string.xd_perm_home_need, need),
            ok ? Style.OK : Style.WARN, Style.TEXT_SMALL);
        sub.setPadding(0, dp(2), 0, 0);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.xd_ic_chevron_down);
        chevron.setRotation(-90);
        chevron.setColorFilter(Style.TEXT_DIM);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.setOnClickListener(v -> {
            Style.tick(v);
            showPermissions();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        c.addView(row, lp);
    }

    /** Settings › Permissions: the three groups, each item with its state and the way to turn it on. */
    private void showPermissions() {
        final SettingsUi s = ui();
        if (permCenter != null && permCenter.isShowing()) {
            Dialog old = permCenter;
            permCenter = null;
            old.dismiss();
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        java.util.List<Permissions.Item> visible = Permissions.visible(this);
        int on = Permissions.onCount(this), need = Permissions.attention(this);

        LinearLayout summary = s.card(c);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(8), dp(10), dp(4));
        if (need > 0) {
            TextView count = Style.label(this, String.valueOf(need), Style.WARN, 22);
            count.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(count);
            TextView what = Style.label(this, getString(R.string.xd_perm_need_suffix), Style.TEXT, Style.TEXT_BODY);
            what.setPadding(dp(6), 0, 0, 0);
            head.addView(what);
        } else {
            TextView fine = Style.label(this, "✓ " + getString(R.string.xd_perm_home_ok), Style.OK, Style.TEXT_BODY);
            fine.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(fine);
        }
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        head.addView(Style.label(this, getString(R.string.xd_perm_on_count, on, visible.size()), Style.TEXT_DIM, Style.TEXT_SMALL));
        summary.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(10), dp(4), dp(10), dp(4));
        for (Permissions.Item item : visible) {
            View segment = new View(this);
            Permissions.State st = item.state(this);
            segment.setBackground(Style.rounded(this,
                st == Permissions.State.ON ? Style.OK : st == Permissions.State.UNKNOWN ? Style.FAINT
                    : item.level == Permissions.Level.WHEN_NEEDED ? Style.FAINT : Style.WARN, 3));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(5), 1);
            sp.leftMargin = sp.rightMargin = dp(1);
            bar.addView(segment, sp);
        }
        summary.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        TextView note = Style.label(this, getString(R.string.xd_perm_no_root), Style.TEXT_DIM, Style.TEXT_SMALL);
        note.setPadding(dp(10), dp(2), dp(10), dp(8));
        summary.addView(note);

        addPermissionGroup(s, c, Permissions.Level.REQUIRED, R.string.xd_perm_group_required);
        addPermissionGroup(s, c, Permissions.Level.RECOMMENDED, R.string.xd_perm_group_recommended);
        addPermissionGroup(s, c, Permissions.Level.WHEN_NEEDED, R.string.xd_perm_group_when);
        permCenter = settingsPage(getString(R.string.xd_perm_title), c, () -> permCenter = null);
    }

    private void addPermissionGroup(SettingsUi s, LinearLayout c, Permissions.Level level, int title) {
        java.util.List<Permissions.Item> items = Permissions.items(this, level);
        if (items.isEmpty()) return;
        LinearLayout card = s.section(c, getString(title));
        for (Permissions.Item item : items) addPermissionRow(card, item);
    }

    private void addPermissionRow(LinearLayout card, Permissions.Item item) {
        Permissions.State state = item.state(this);
        boolean attention = state != Permissions.State.ON && item.level != Permissions.Level.WHEN_NEEDED;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(Style.ripple(this, Color.TRANSPARENT, Style.RADIUS));
        row.setClickable(true);
        row.setFocusable(false);
        View dot = new View(this);
        dot.setBackground(Style.dot(state == Permissions.State.ON ? Style.OK
            : state == Permissions.State.UNKNOWN ? Style.FAINT : attention ? Style.WARN : Style.FAINT));
        LinearLayout.LayoutParams dp8 = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp8.rightMargin = dp(12);
        row.addView(dot, dp8);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Style.label(this, getString(item.name), Style.TEXT, Style.TEXT_BODY));
        TextView use = Style.label(this, getString(item.use), Style.TEXT_DIM, Style.TEXT_SMALL);
        use.setPadding(0, dp(2), 0, 0);
        text.addView(use);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (state == Permissions.State.ON) {
            row.addView(Style.label(this, getString(R.string.xd_perm_on), Style.OK, Style.TEXT_SMALL));
        } else {
            if (state == Permissions.State.UNKNOWN)
                row.addView(Style.label(this, getString(R.string.xd_perm_unknown), Style.TEXT_DIM, Style.TEXT_SMALL));
            TextView go = Style.button(this, getString(item.action),
                attention || state == Permissions.State.UNKNOWN ? Style.WARN : Style.SURFACE_RAISED, Style.TEXT_SMALL, 15);
            go.setTextColor(attention || state == Permissions.State.UNKNOWN ? 0xFF1A1405 : Style.TEXT);
            go.setPadding(dp(12), 0, dp(12), 0);
            go.setOnClickListener(v -> {
                Style.tick(v);
                openPermissionFix(item);
            });
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, dp(30));
            gp.leftMargin = dp(10);
            row.addView(go, gp);
        }
        row.setOnClickListener(v -> {
            Style.tick(v);
            showPermissionDetail(item);
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One permission explained: what it is for, what is missing without it, how to turn it on, and the button. */
    void showPermissionDetail(Permissions.Item item) {
        if (permDetail != null && permDetail.isShowing()) {
            Dialog old = permDetail;
            permDetail = null;
            old.dismiss();
        }
        final SettingsUi s = ui();
        permDetailItem = item;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        Permissions.State state = item.state(this);
        LinearLayout pills = new LinearLayout(this);
        pills.setPadding(dp(4), dp(2), dp(4), dp(2));
        pills.addView(permissionPill(getString(state == Permissions.State.ON ? R.string.xd_perm_on
            : state == Permissions.State.UNKNOWN ? R.string.xd_perm_unknown : R.string.xd_perm_off),
            state == Permissions.State.ON ? Style.OK : state == Permissions.State.UNKNOWN ? Style.TEXT_DIM : Style.WARN,
            state == Permissions.State.ON ? 0x1F3FCF7F : state == Permissions.State.UNKNOWN ? Style.SURFACE_RAISED : 0x29F0883E));
        int level = item.level == Permissions.Level.REQUIRED ? R.string.xd_perm_group_required
            : item.level == Permissions.Level.RECOMMENDED ? R.string.xd_perm_group_recommended : R.string.xd_perm_group_when;
        pills.addView(permissionPill(getString(level), Style.TEXT_DIM, Style.SURFACE_RAISED));
        c.addView(pills, new LinearLayout.LayoutParams(-1, -2));

        addPermissionText(s, c, R.string.xd_perm_detail_use, getString(item.why));
        addPermissionText(s, c, R.string.xd_perm_detail_without, getString(item.without));

        LinearLayout how = s.section(c, getString(R.string.xd_perm_detail_how));
        String[] steps = getString(item.steps).split("\n");
        for (int i = 0; i < steps.length; i++) {
            LinearLayout step = new LinearLayout(this);
            step.setGravity(Gravity.CENTER_VERTICAL);
            step.setPadding(dp(10), dp(6), dp(10), dp(6));
            TextView num = Style.label(this, String.valueOf(i + 1), Style.ACCENT_2, Style.TEXT_SMALL);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Style.rounded(this, Style.SURFACE_RAISED, 11));
            step.addView(num, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView text = Style.label(this, steps[i], Style.TEXT, 14);
            text.setPadding(dp(12), 0, 0, 0);
            text.setLineSpacing(dp(2), 1f);
            step.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            how.addView(step, new LinearLayout.LayoutParams(-1, -2));
        }

        if (state != Permissions.State.ON) {
            // The page's one primary action is the blue button (PermDetail.dc.html)
            TextView go = Style.button(this, getString(item.action), Style.ACCENT, Style.TEXT_BODY + 1, 12);
            go.setOnClickListener(v -> {
                Style.tick(v);
                openPermissionFix(item);
            });
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(50));
            gp.topMargin = dp(18);
            gp.leftMargin = gp.rightMargin = dp(4);
            c.addView(go, gp);
            if (item == Permissions.INJECTOR) {
                TextView shizuku = Style.button(this, getString(R.string.xd_perm_show_shizuku), Style.SURFACE_RAISED,
                    Style.TEXT_BODY, 12);
                shizuku.setOnClickListener(v -> {
                    Style.tick(v);
                    openShizukuFlow();
                });
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(46));
                sp.topMargin = dp(8);
                sp.leftMargin = sp.rightMargin = dp(4);
                c.addView(shizuku, sp);
            } else if (item.runtime != null) {
                // Refused for good, or a switch the system greys out: the details page can still allow it
                TextView details = Style.label(this, getString(R.string.xd_permit_details), Style.ACCENT_2, Style.TEXT_SMALL + 1);
                details.setPadding(dp(4), dp(12), dp(4), dp(4));
                details.setOnClickListener(v -> {
                    Style.tick(v);
                    openAppDetails();
                });
                c.addView(details);
            }
        }
        permDetail = settingsPage(getString(item.name), c, () -> permDetail = null);
    }

    private void addPermissionText(SettingsUi s, LinearLayout c, int title, String body) {
        LinearLayout card = s.section(c, getString(title));
        TextView t = Style.label(this, body, Style.TEXT, 14);
        t.setPadding(dp(10), dp(6), dp(10), dp(10));
        t.setLineSpacing(dp(2), 1f);
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView permissionPill(String text, int color, int background) {
        TextView pill = Style.label(this, text, color, Style.TEXT_SMALL);
        pill.setPadding(dp(10), dp(3), dp(10), dp(3));
        pill.setBackground(Style.rounded(this, background, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        pill.setLayoutParams(lp);
        return pill;
    }

    /** The button that fixes one item: the system page, the system's ask, or XDesk's own activation flow. */
    private void openPermissionFix(Permissions.Item item) {
        if (item == Permissions.INJECTOR) {
            openInjectorFlow();
            return;
        }
        if (item == Permissions.OVERLAY) {
            openOverlaySettings();
            return;
        }
        if (item == Permissions.NOTIFY_ACCESS) {
            NotifyCenter.openAccessSettings(this);
            return;
        }
        if (item == Permissions.USB_DEBUG) {
            openWirelessSettings();
            return;
        }
        if (item == Permissions.AUTOSTART) {
            NotifyCenter.openAutostartSettings(this);
            return;
        }
        if (item == Permissions.NCM) {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("adb",
                "adb shell settings put global tether_force_usb_functions 1"));
            Toast.makeText(this, R.string.xd_perm_copied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.runtime != null) {
            requestPermissions(new String[]{item.runtime}, REQ_PERM_CENTER);
            return;
        }
        Intent page = item.page(this);
        if (page == null) return;
        try {
            startActivity(page, PermitCard.onPhone());
        } catch (RuntimeException e) {
            Log.w(TAG, "permission page " + item.id, e);
            Toast.makeText(this, R.string.xd_perm_no_page, Toast.LENGTH_SHORT).show();
        }
    }

    /** The injector goes through the one activation page; Shizuku through the one Shizuku setup page. */
    private void openInjectorFlow() {
        retryRequested = true;
        activationFlow = true;
        ShellActivation shell = apps.shell();
        if (!shell.isPaired() || shell.isPairing()) {
            // The pairing page is a dialog: it sits above the first-start checklist, which then checks itself
            showActivation(null);
            return;
        }
        // Paired but not running: the setup page is behind the dialogs, so the settings pages go first
        closeSettings();
        dismissOnboard();
        showActivation(null);
    }

    private void openShizukuFlow() {
        closeSettings();
        dismissOnboard();
        shizukuScreens = true;
        activationFlow = true;
        closeActivation();
        showShizukuSetup();
    }

    private void dismissOnboard() {
        if (permOnboard != null) {
            Dialog old = permOnboard;
            permOnboard = null;
            permRebuilding = true;
            old.dismiss();
            permRebuilding = false;
        }
    }

    /** The pages above stay in step with the system: XDesk came back, or the system's ask was answered. */
    private void refreshPermissionPages() {
        updatePermissionBadges();
        if (permDetail != null && permDetail.isShowing()) {
            Permissions.Item item = permDetailItem;
            // The centre behind it is stale too; built first so the rebuilt detail stays on top
            if (permCenter != null && permCenter.isShowing()) {
                Dialog stale = permCenter;
                permCenter = null;
                stale.dismiss();
                showPermissions();
            }
            Dialog old = permDetail;
            permDetail = null;
            old.dismiss();
            if (item != null) showPermissionDetail(item);
            return;
        }
        if (permCenter != null && permCenter.isShowing()) {
            Dialog old = permCenter;
            permCenter = null;
            old.dismiss();
            showPermissions();
        }
        if (permOnboard != null && permOnboard.isShowing()) {
            Dialog old = permOnboard;
            permOnboard = null;
            old.dismiss();
            showPermissionOnboard();
        }
    }

    // --- 首次启动：开始之前 ---

    /**
     * Once on first start: the required and recommended permissions with a progress line, each opening its flow.
     * Skipped or finished is remembered. A user updating with both required permissions already on never sees it; a
     * user who left it without finishing sees it again only while a required permission is still missing.
     */
    private void maybeShowPermissionOnboard() {
        if (isFinishing() || isDestroyed()) return;
        boolean seen = prefs.getBoolean(PREF_PERM_ONBOARD_SEEN, false);
        boolean done = prefs.getBoolean(PREF_PERM_ONBOARD_DONE, false);
        // An install that was here before 0.7.0 (updated, or with settings from those versions) is an existing
        // user: the checklist never comes up by itself for them, whatever their permissions are now. This also
        // covers a Shizuku user whose Shizuku has not started yet: nothing looks missing on their first 0.7.0 run.
        if (!done && (installWasUpdated() || (!seen && oldPrefsAtStart))) {
            prefs.edit().putBoolean(PREF_PERM_ONBOARD_SEEN, true)
                .putBoolean(PREF_PERM_ONBOARD_DONE, true).apply();
            return;
        }
        // A paired injector counts as set up even while its server is still starting: an updating user must not be
        // taken for a first start just because onCreate ran before the wireless-debugging server came back
        boolean injector = Permissions.INJECTOR.granted(this) || apps.shell().isPaired();
        boolean requiredOn = injector && Permissions.OVERLAY.granted(this);
        if (!seen) {
            prefs.edit().putBoolean(PREF_PERM_ONBOARD_SEEN, true).apply();
            if (requiredOn) {
                // An existing install that already has what it needs: no page, as the owner asked
                prefs.edit().putBoolean(PREF_PERM_ONBOARD_DONE, true).apply();
                return;
            }
            showPermissionOnboard();
        } else if (!done && !requiredOn) {
            showPermissionOnboard();
        }
    }

    /** The APK was installed over an older one: lastUpdateTime is firstInstallTime only for a fresh install. */
    private boolean installWasUpdated() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.lastUpdateTime > p.firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The prefs file held a key from before 0.7.0. The 0.7.0-only keys are its first-start state, the phone
     * interface and the dim choice (a 0.6.6 dim_idle is migrated to it by onCreate, hence the read before that).
     */
    private boolean hasPrefsFromBefore070() {
        for (String key : prefs.getAll().keySet()) {
            if (key.equals(PREF_PERM_ONBOARD_SEEN) || key.equals(PREF_PERM_ONBOARD_DONE)
                || key.equals(PREF_PHONE_UI) || key.equals(PREF_DIM)) continue;
            return true;
        }
        return false;
    }

    private void showPermissionOnboard() {
        if (prefs.getBoolean(PREF_PERM_ONBOARD_DONE, false)) return;
        if (permOnboard != null && permOnboard.isShowing()) {
            Dialog old = permOnboard;
            permOnboard = null;
            old.dismiss();
        }
        final SettingsUi s = ui();
        java.util.List<Permissions.Item> items = new java.util.ArrayList<>();
        for (Permissions.Item item : Permissions.ALL)
            if (item.level != Permissions.Level.WHEN_NEEDED && item.visible(this)) items.add(item);
        boolean allOn = true;
        for (Permissions.Item item : items) if (!item.granted(this)) allOn = false;
        if (allOn) {
            // Finished, perhaps by fixing the last one while the page was open
            prefs.edit().putBoolean(PREF_PERM_ONBOARD_DONE, true).apply();
            return;
        }

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView intro = Style.label(this, getString(R.string.xd_perm_onboard_intro), Style.TEXT_DIM, 14);
        intro.setPadding(dp(4), dp(4), dp(4), dp(10));
        c.addView(intro);

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(4), 0, dp(4), dp(10));
        for (Permissions.Item item : items) {
            View segment = new View(this);
            segment.setBackground(Style.rounded(this, item.granted(this) ? Style.OK
                : item.level == Permissions.Level.REQUIRED ? Style.ACCENT : Style.SURFACE_RAISED, 3));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(5), 1);
            sp.leftMargin = sp.rightMargin = dp(1);
            bar.addView(segment, sp);
        }
        c.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        boolean highlighted = false;
        for (int i = 0; i < items.size(); i++) {
            Permissions.Item item = items.get(i);
            boolean on = item.granted(this);
            boolean required = item.level == Permissions.Level.REQUIRED;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(12), dp(12));
            GradientDrawable back = Style.rounded(this, Style.SURFACE, 16);
            if (!on && required && !highlighted) {
                back.setStroke(dp(2), Style.ACCENT);
                highlighted = true;
            }
            row.setBackground(back);
            // A finished item has nothing to open: the checklist is about turning things on
            row.setClickable(!on);
            row.setFocusable(false);

            TextView mark = Style.label(this, on ? "✓" : String.valueOf(i + 1),
                on ? 0xFF0B0D10 : required ? Style.TEXT : Style.TEXT_DIM, 13);
            mark.setGravity(Gravity.CENTER);
            mark.setTypeface(Typeface.DEFAULT_BOLD);
            mark.setBackground(Style.rounded(this, on ? Style.OK : required ? Style.ACCENT : Style.SURFACE_RAISED, 14));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(28), dp(28));
            mp.rightMargin = dp(14);
            row.addView(mark, mp);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(Style.label(this, getString(item.name), on ? Style.TEXT_DIM : Style.TEXT, Style.TEXT_BODY));
            TextView tag = Style.label(this, getString(required ? R.string.xd_perm_group_required : R.string.xd_perm_group_recommended),
                required ? Style.DANGER : Style.TEXT_DIM, 11);
            tag.setPadding(dp(7), dp(1), dp(7), dp(1));
            tag.setBackground(Style.rounded(this, required ? Style.DANGER_TINT : Style.SURFACE_RAISED, 8));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.leftMargin = dp(6);
            titleRow.addView(tag, tp);
            text.addView(titleRow);
            TextView state = Style.label(this, on ? getString(R.string.xd_perm_on) : getString(item.use),
                on ? Style.OK : Style.TEXT_DIM, Style.TEXT_SMALL);
            state.setPadding(0, dp(3), 0, 0);
            text.addView(state);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            if (!on) {
                // The checklist's action is the blue primary button (PermOnboard.dc.html)
                TextView go = Style.button(this, getString(item.action), required ? Style.ACCENT : Style.SURFACE_RAISED,
                    Style.TEXT_SMALL, 15);
                go.setPadding(dp(12), 0, dp(12), 0);
                go.setOnClickListener(v -> {
                    Style.tick(v);
                    openPermissionFix(item);
                });
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, dp(30));
                gp.leftMargin = dp(10);
                row.addView(go, gp);
            }
            if (!on) row.setOnClickListener(v -> {
                Style.tick(v);
                openPermissionFix(item);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(8);
            c.addView(row, rp);
        }

        TextView skip = Style.button(this, getString(R.string.xd_perm_onboard_skip), Style.SURFACE_RAISED, Style.TEXT_BODY, 12);
        skip.setOnClickListener(v -> {
            Style.tick(v);
            prefs.edit().putBoolean(PREF_PERM_ONBOARD_DONE, true).apply();
            dismissOnboard();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48));
        sp.topMargin = dp(14);
        c.addView(skip, sp);
        permOnboard = settingsPage(getString(R.string.xd_perm_onboard_title), c, () -> {
            if (!permRebuilding) permOnboard = null;
        });
    }

    // --- 失效提醒：曾经开着的必需权限现在关了 ---

    /**
     * Once per app start for each required permission: the mockup's orange bar at the top, a notification, and a
     * button that opens the item. The delayed calls give the injector its moment to come back after a reboot; the
     * bar itself is rebuilt from every reminded item still off, so it survives the window being recreated.
     */
    private void checkPermissionAlerts(boolean judgeInjector) {
        if (screens == null || isFinishing() || isDestroyed()) return;
        java.util.List<Permissions.Item> lost = Permissions.lost(this, judgeInjector);
        java.util.List<Permissions.Item> stillOff = Permissions.remindedOff(this);
        // A new loss posts the notification, once per item per process; it lists every reminded item still off
        if (!lost.isEmpty()) Permissions.notifyLost(this, stillOff);
        permAlerts.clear();
        permAlerts.addAll(stillOff);
        if (permAlerts.isEmpty()) Permissions.cancelLost(this);
        showPermissionBanner();
        updatePermissionBadges();
    }

    /** PermExpired.dc.html's red dot: the console's settings button and the Launchpad bar's, while required is off. */
    private void updatePermissionBadges() {
        boolean off = Permissions.requiredOff(this);
        if (settingsBtn != null) settingsBtn.setBadge(off);
        if (launchpad != null) launchpad.setSettingsBadge(off);
    }

    private void showPermissionBanner() {
        if (permAlerts.isEmpty()) {
            if (permBanner != null) {
                screens.removeView(permBanner);
                permBanner = null;
            }
            return;
        }
        final String text = permAlerts.size() == 1 ? getString(permAlerts.get(0).lost)
            : getString(R.string.xd_perm_lost_many, permAlerts.size());
        // The delayed checks say the same thing: keep the bar, do not make it blink
        if (permBanner != null && text.equals(permBanner.getTag())) return;
        if (permBanner != null) screens.removeView(permBanner);
        LinearLayout banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = Style.rounded(this, (Style.WARN & 0x00FFFFFF) | 0x1FF0883E, 12);
        bg.setStroke(dp(1), (Style.WARN & 0x00FFFFFF) | 0x4DF0883E);
        banner.setBackground(bg);
        View dot = new View(this);
        dot.setBackground(Style.dot(Style.WARN));
        LinearLayout.LayoutParams dp8 = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp8.rightMargin = dp(10);
        banner.addView(dot, dp8);
        TextView message = Style.label(this, text, Style.TEXT, 13);
        message.setSingleLine(true);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        banner.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
        TextView fix = Style.label(this, getString(R.string.xd_perm_reminder_fix), Style.WARN, 13);
        fix.setTypeface(Typeface.DEFAULT_BOLD);
        fix.setPadding(dp(10), 0, 0, 0);
        fix.setOnClickListener(v -> {
            Style.tick(v);
            if (permAlerts.size() == 1) showPermissionDetail(permAlerts.get(0));
            else showPermissions();
        });
        banner.addView(fix);
        banner.setOnClickListener(v -> {
            Style.tick(v);
            if (permAlerts.size() == 1) showPermissionDetail(permAlerts.get(0));
            else showPermissions();
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(44));
        lp.gravity = Gravity.TOP;
        lp.leftMargin = lp.rightMargin = lp.topMargin = dp(8);
        banner.setElevation(dp(6));
        banner.setTag(text);
        permBanner = banner;
        screens.addView(banner, lp);
    }

    private void showPhoneSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout show = s.card(c);
        // A global choice, not per screen type; the running screen is built again at once (applyPhoneUi)
        s.segmentedRow(show, getString(R.string.xd_phone_ui), getString(R.string.xd_phone_ui_note),
            new String[]{getString(R.string.xd_phone_ui_console), getString(R.string.xd_phone_ui_launchpad)},
            launchpadMode() ? 1 : 0, which -> {
                prefs.edit().putString(PREF_PHONE_UI, which == 1 ? UI_LAUNCHPAD : UI_CONSOLE).apply();
                applyPhoneUi();
            });
        s.segmentedRow(show, getString(R.string.xd_dim), getString(R.string.xd_dim_note), strings(DIM_LABELS),
            dimChoiceIndex(prefs.getInt(PREF_DIM, DIM_DEFAULT_SECONDS)), which -> {
                prefs.edit().putInt(PREF_DIM, DIM_SECONDS[which]).apply();
                bumpIdle();
            });
        int orientation = 2 - prefs.getInt(PREF_ORIENTATION, ORIENTATION_DEFAULT);
        s.segmentedRow(show, getString(R.string.xd_orientation), null, strings(ORIENTATION_LABELS), orientation, which -> {
            prefs.edit().putInt(PREF_ORIENTATION, ORIENTATION_VALUES[which]).apply();
            applyOrientation();
        });
        // 安卓 13 起才有按应用设语言；更早的只跟随系统。系统应用信息里也能改，两边是同一个设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            final android.app.LocaleManager locales = getSystemService(android.app.LocaleManager.class);
            android.os.LocaleList now = locales.getApplicationLocales();
            int language = now.isEmpty() ? 0 : "zh".equals(now.get(0).getLanguage()) ? 2 : 1;
            s.segmentedRow(show, getString(R.string.xd_language), null,
                new String[]{getString(R.string.xd_language_system), getString(R.string.xd_language_en), getString(R.string.xd_language_zh)},
                language, which -> {
                    // 系统接着会重建界面，设置页先关掉
                    closeSettings();
                    locales.setApplicationLocales(which == 0 ? android.os.LocaleList.getEmptyLocaleList()
                        : android.os.LocaleList.forLanguageTags(which == 1 ? "en" : "zh-CN"));
                });
        }
        settingsPage(getString(R.string.xd_section_phone), c, null);
    }

    /** INPUT.md §12: the settings go to the receivers as SETTINGS (type 19), in the units the phone's own settings use. */
    private void updateReceiverSettings() {
        if (wireless == null) return;
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("naturalScroll", prefs.getBoolean(PREF_NATURAL_SCROLL, true));
            j.put("pointerSpeed", (double) prefs.getFloat(PREF_SPEED, 1f));
            j.put("scrollSpeed", (double) prefs.getFloat(PREF_SCROLL_SPEED, 1f));
            wireless.setSettings(j.toString());
        } catch (org.json.JSONException e) {
            Log.w(TAG, "settings json", e);
        }
    }

    private void showInputSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        boolean appMode = apps.isActive();
        // SetInput: the touchpad first, then the keyboard, then the mouse
        LinearLayout input = s.section(c, getString(R.string.xd_chooser_touchpad));
        s.percentRow(input, getString(R.string.xd_pad_speed), null, Math.round(prefs.getFloat(PREF_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            touchpad.setSpeed(v);
            directTouch.setSpeed(v);
            prefs.edit().putFloat(PREF_SPEED, v).apply();
            updateReceiverSettings();
        });
        s.percentRow(input, getString(R.string.xd_scroll_speed), getString(R.string.xd_scroll_speed_note), Math.round(prefs.getFloat(PREF_SCROLL_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            touchpad.setScrollSpeed(v);
            wheelSpeed = v;
            prefs.edit().putFloat(PREF_SCROLL_SPEED, v).apply();
            updateReceiverSettings();
        });
        // INPUT.md §12: one setting for every device
        s.switchRow(input, getString(R.string.xd_natural_scroll), getString(R.string.xd_natural_scroll_note), prefs.getBoolean(PREF_NATURAL_SCROLL, true), on -> {
            prefs.edit().putBoolean(PREF_NATURAL_SCROLL, on).apply();
            touchpad.setNaturalScroll(on);
            updateReceiverSettings();
        });
        LinearLayout keys = s.section(c, getString(R.string.xd_linux_keyboard));
        s.switchRow(keys, getString(R.string.xd_keys_via_ime), getString(R.string.xd_keys_via_ime_note), prefs.getBoolean(PREF_KEYS_VIA_IME, true), on -> {
            prefs.edit().putBoolean(PREF_KEYS_VIA_IME, on).apply();
            // Devices that follow the global setting read this when their keys come
            MacInput.keysViaIme = on;
            // The phone's window is the way now: the desktop window's takeover of the old way ends
            if (on && desktopTaken) setDesktopTaken(false);
            updateCapture();
            updateStatus();
            syncKeyMode();
        });
        s.switchRow(keys, getString(R.string.xd_ime_corrections), getString(R.string.xd_ime_corrections_note), prefs.getBoolean(PREF_IME_CORRECTIONS, true), on -> {
            prefs.edit().putBoolean(PREF_IME_CORRECTIONS, on).apply();
            if (keyView != null) keyView.bridge().setCorrections(on);
        });
        s.switchRow(keys, getString(R.string.xd_translate_modifiers), getString(R.string.xd_translate_modifiers_note), prefs.getBoolean(PREF_TRANSLATE_MODIFIERS, true), on -> {
            prefs.edit().putBoolean(PREF_TRANSLATE_MODIFIERS, on).apply();
            MacInput.translateModifiers = on;
        });
        LinearLayout mouse = s.section(c, getString(R.string.xd_mouse));
        // Every mouse: the injector's for Android apps, the captured one for the X desktop and Macs (INPUT.md §12)
        s.percentRow(mouse, getString(R.string.xd_mouse_speed), null, Math.round(prefs.getFloat(PREF_MOUSE_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            prefs.edit().putFloat(PREF_MOUSE_SPEED, v).apply();
            mouseSpeed = v;
            apps.setMouseSpeed(v);
        });
        if (appMode) {
            s.switchRow(mouse, getString(R.string.xd_always_cursor), getString(R.string.xd_always_cursor_note), prefs.getBoolean(PREF_ALWAYS_CURSOR, false), on -> {
                prefs.edit().putBoolean(PREF_ALWAYS_CURSOR, on).apply();
                apps.setAlwaysShowCursor(on);
            });
        }
        settingsPage(getString(R.string.xd_section_input), c, null);
    }

    private void showDisplaySettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        boolean appMode = apps.isActive();
        // SetDisplay: this screen, every screen, Android apps, the Linux desktop
        Display screen = externalDisplay();
        LinearLayout external = s.section(c,
            screen != null ? getString(R.string.xd_display_this_screen, screen.getName()) : getString(R.string.xd_display_this_screen_none));
        DesktopScale.addSettings(this, s, external, screen, this::screenSizingChanged);
        LinearLayout allScreens = s.section(c, getString(R.string.xd_display_general));
        s.switchRow(allScreens, getString(R.string.xd_external_dock), getString(R.string.xd_external_dock_note), prefs.getBoolean(PREF_EXTERNAL_DOCK, true), on -> {
            prefs.edit().putBoolean(PREF_EXTERNAL_DOCK, on).apply();
            updateExternalDock();
        });
        String cover = getString(Settings.canDrawOverlays(this) ? R.string.xd_cover_note : R.string.xd_cover_note_no_overlay);
        // Missing permission said in place: the feature stays on the page, greyed, with the orange line (PermInline)
        s.switchRow(allScreens, getString(R.string.xd_cover), cover, prefs.getBoolean(PREF_COVER, true),
            Settings.canDrawOverlays(this) ? null : Permissions.need(this, Permissions.OVERLAY),
            () -> showPermissionDetail(Permissions.OVERLAY), on -> {
                prefs.edit().putBoolean(PREF_COVER, on).apply();
            });
        LinearLayout android = s.section(c, getString(R.string.xd_display_android));
        addDensitySetting(s, android);
        if (AppSession.fullscreenSupported()) {
            s.switchRow(android, getString(R.string.xd_fullscreen_default), getString(R.string.xd_fullscreen_default_note), apps.isFullscreenDefault(), on -> apps.setFullscreenDefault(on));
            s.stopRowIn(android, getString(R.string.xd_fullscreen_reset), null, () ->
                s.confirm(getString(R.string.xd_fullscreen_reset_title), getString(R.string.xd_fullscreen_reset_message), getString(R.string.xd_fullscreen_reset_action), () -> {
                    if (!apps.resetFullscreen()) Toast.makeText(this, R.string.xd_fullscreen_reset_need_injector, Toast.LENGTH_LONG).show();
                }));
        }
        if (!appMode) {
            LinearLayout linux = s.section(c, getString(R.string.xd_display_linux));
            final TextView[] resolution = new TextView[1];
            resolution[0] = s.linkRow(linux, getString(R.string.xd_resolution), getString(R.string.xd_resolution_note), resolutionLabel(resolutionMode(), screen), () -> chooseResolution(resolution[0]));
        }
        settingsPage(getString(R.string.xd_section_display), c, null);
    }

    /**
     * Once, the first time an external screen (not a car) is used without the notification access: what the access
     * gives and where it is turned on later. "Later" is an answer too: never asked again, the settings row stays.
     */
    private void offerNotifyAccess() {
        if (prefs.getBoolean(PREF_NOTIFY_ACCESS_OFFERED, false) || isFinishing()) return;
        Display d = externalDisplay();
        if (d == null || TargetDisplay.isCar(d)) return;
        prefs.edit().putBoolean(PREF_NOTIFY_ACCESS_OFFERED, true).putBoolean(PREF_NOTIFY_ACCESS_ASKED, true).apply();
        ui.post(() -> {
            if (isFinishing() || NotifyCenter.accessGranted(this)) return;
            ui().permitSheet(getString(R.string.xd_permit_notify_access), getString(R.string.xd_notify_access_offer),
                getString(R.string.xd_permit_list_path), null, () -> NotifyCenter.openAccessSettings(this), null);
        });
    }

    private boolean swallowNoAccessN;

    /** Ctrl+Alt+N while the centre cannot be there (no notification access): a word on the phone, the key is taken. */
    private boolean notifyAccessKey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_N) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && swallowNoAccessN) {
            swallowNoAccessN = false;
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || !e.isCtrlPressed() || !e.isAltPressed()) return false;
        if (NotifyCenter.accessGranted(this) || externalDisplay() == null) return false;
        swallowNoAccessN = true;
        if (e.getRepeatCount() == 0) {
            // The shortcut that opens the centre needs the access: the sheet, not a toast that goes away (PermSheet)
            ui().permitSheet(getString(R.string.xd_permit_notify_access), getString(R.string.xd_permit_notify_access_why),
                getString(R.string.xd_permit_list_path), null, () -> NotifyCenter.openAccessSettings(this), null);
        }
        return true;
    }

    /** NOTIFY.md §6: what shows on the external screen, what banners say, per app, the low-battery banners. */
    private void showNotifySettings() {
        final SettingsUi s = ui();
        // A card that turns green rebuilds the page, so the rows below say what is true now
        final Dialog[] page = new Dialog[1];
        final Runnable rebuild = () -> {
            if (page[0] != null && page[0].isShowing()) {
                page[0].dismiss();
                showNotifySettings();
            }
        };
        final NotifyCenter center = NotifyCenter.get(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        boolean granted = NotifyCenter.accessGranted(this);
        // PermitCard (owner, 2026-11-18): what is missing is said at the top, in the one look of a permission to turn on
        if (!granted) {
            PermitCard card = s.permit(getString(R.string.xd_permit_notify_access), getString(R.string.xd_permit_notify_access_why),
                getString(R.string.xd_permit_list_path), () -> NotifyCenter.openAccessSettings(this),
                () -> NotifyCenter.accessGranted(this));
            card.onDone(rebuild);
            // Android 13 on: an app not installed by a store must first be let through ("restricted settings"), else the
            // system's switch is grey. Where to do that, and the app's details page to do it on (owner, 2026-11-15)
            if (android.os.Build.VERSION.SDK_INT >= 33) card.link(getString(R.string.xd_permit_restricted_link), this::openAppDetails);
            card.addTo(c);
        } else if (!center.connected()) {
            // NOTIFY.md §2.1: granted is not bound. MIUI: XDesk may not start itself; elsewhere the switch once more
            boolean miui = NotifyCenter.hasAutostartSwitch(this);
            PermitCard link = s.permit(getString(miui ? R.string.xd_permit_autostart : R.string.xd_permit_notify_access),
                getString(miui ? R.string.xd_permit_autostart_why : R.string.xd_permit_rebind_why),
                getString(miui ? R.string.xd_permit_autostart_path : R.string.xd_permit_list_path), () -> {
                    center.rebindIfNeeded();
                    if (miui) NotifyCenter.openAutostartSettings(this);
                    else NotifyCenter.openAccessSettings(this);
                }, center::connected).onDone(rebuild).addTo(c);
            if (!miui) link.retitle(getString(R.string.xd_permit_title_again, getString(R.string.xd_permit_notify_access)));
        }
        LinearLayout show = s.card(c);
        // Without the access the switch is grey and the orange line opens the permission's page (PermInline)
        s.switchRow(show, getString(R.string.xd_notify_on), getString(R.string.xd_notify_on_note), center.notifyOn(),
            granted ? null : Permissions.need(this, Permissions.NOTIFY_ACCESS),
            () -> showPermissionDetail(Permissions.NOTIFY_ACCESS), center::setNotifyOn);
        s.segmentedRow(show, getString(R.string.xd_notify_content), null,
            new String[]{getString(R.string.xd_notify_content_all), getString(R.string.xd_notify_content_name), getString(R.string.xd_notify_content_none)},
            center.bannerContent(), center::setBannerContent);
        s.switchRow(show, getString(R.string.xd_notify_battery_banner), getString(R.string.xd_notify_battery_banner_note),
            center.lowBatteryBanners(), center::setLowBatteryBanners);
        int shownApps = center.recentApps().size();
        s.linkRow(show, getString(R.string.xd_notify_per_app), null, shownApps > 0 ? String.valueOf(shownApps) : "",
            this::showNotifyApps);
        // SetNotify's 状态: the access and the service, each saying what it is now
        LinearLayout state = s.section(c, getString(R.string.xd_notify_state));
        s.valueRow(state, getString(R.string.xd_notify_access), null,
            getString(granted ? R.string.xd_notify_access_on : R.string.xd_notify_access_short_off),
            granted ? Style.OK : Style.WARN, () -> NotifyCenter.openAccessSettings(this));
        boolean bound = center.connected();
        s.valueRow(state, getString(R.string.xd_notify_link), null,
            getString(bound ? R.string.xd_notify_link_on : R.string.xd_notify_link_off),
            bound ? Style.OK : Style.WARN, () -> {
                center.rebindIfNeeded();
                if (NotifyCenter.hasAutostartSwitch(this)) NotifyCenter.openAutostartSettings(this);
                else NotifyCenter.openAccessSettings(this);
            });
        TextView privacy = Style.label(this, getString(R.string.xd_notify_privacy_note), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        privacy.setPadding(dp(6), dp(8), dp(6), dp(2));
        c.addView(privacy);
        page[0] = settingsPage(getString(R.string.xd_section_notifications), c, null);
    }

    /** The per-app page of NOTIFY.md §6: the apps that posted recently, each with its own choice. */
    private void showNotifyApps() {
        final SettingsUi s = ui();
        final NotifyCenter center = NotifyCenter.get(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = s.card(c);
        java.util.List<String> apps = center.recentApps();
        if (apps.isEmpty()) {
            s.infoRow(card, getString(R.string.xd_notify_per_app), getString(R.string.xd_notify_per_app_note), null);
        } else {
            final String[] modes = {getString(R.string.xd_notify_mode_banner), getString(R.string.xd_notify_mode_center), getString(R.string.xd_notify_mode_hidden)};
            for (final String pkg : apps) {
                final String label = center.appLabel(pkg);
                final int at = center.appMode(pkg);
                final TextView[] value = new TextView[1];
                value[0] = s.selectRow(card, label, modes[at], at == 2 ? Style.DANGER : Style.TEXT_DIM,
                    () -> s.choice(label, null, modes, center.appMode(pkg), which -> {
                        center.setAppMode(pkg, which);
                        value[0].setText(modes[which]);
                        value[0].setTextColor(which == 2 ? Style.DANGER : Style.TEXT_DIM);
                    }));
            }
            pageNote(c, R.string.xd_notify_apps_note);
        }
        settingsPage(getString(R.string.xd_notify_per_app), c, null);
    }

    private void x11Changed(String key) {
        sendBroadcast(new Intent("com.termux.x11.ACTION_PREFERENCES_CHANGED").putExtra("key", key).putExtra("fromBroadcast", true).setPackage(getPackageName()));
    }

    private void showX11Settings() {
        final SettingsUi s = ui();
        final Prefs p = ((LorieApp) getApplication()).getPrefs(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        pageNote(c, R.string.xd_x11_settings_note);
        LinearLayout card = s.card(c);
        s.switchRow(card, getString(R.string.xd_x11_clipboard), null, p.clipboardEnable.get(), on -> {
            p.clipboardEnable.put(on);
            x11Changed("clipboardEnable");
        });
        s.segmentedRow(card, getString(R.string.xd_x11_filtering), null, new String[]{getString(R.string.xd_x11_filter_smooth), getString(R.string.xd_x11_filter_sharp)},
            "nearest".equals(p.displayFilteringMode.get()) ? 1 : 0, which -> {
                p.displayFilteringMode.put(which == 1 ? "nearest" : "bilinear");
                x11Changed("displayFilteringMode");
            });
        // The preference allows 1 to 300: the slider covers all of it, a saved value is never clamped by a drag
        s.percentRow(card, getString(R.string.xd_x11_mouse_speed), null, p.capturedPointerSpeedFactor.get(), 1, 300, v -> {
            p.putInt("capturedPointerSpeedFactor", v);
            x11Changed("capturedPointerSpeedFactor");
        });
        s.switchRow(card, getString(R.string.xd_x11_scancodes), getString(R.string.xd_x11_scancodes_note), p.preferScancodes.get(), on -> {
            p.preferScancodes.put(on);
            x11Changed("preferScancodes");
        });
        s.switchRow(card, getString(R.string.xd_x11_meta), getString(R.string.xd_x11_meta_note), p.dexMetaKeyCapture.get(), on -> {
            p.dexMetaKeyCapture.put(on);
            x11Changed("dexMetaKeyCapture");
        });
        LinearLayout advanced = s.section(c, getString(R.string.xd_section_other));
        s.linkRow(advanced, getString(R.string.xd_x11_all), getString(R.string.xd_x11_all_note), "",
            () -> startActivity(new Intent(this, LoriePreferences.class)));
        settingsPage(getString(R.string.xd_x11_settings), c, null);
    }

    /** About › Licences: XDesk's GPL-3.0 and the third-party notices it carries (assets/licenses). */
    private void showLicenses() {
        StringBuilder all = new StringBuilder();
        try {
            String[] names = getAssets().list("licenses");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    all.append("==== ").append(n).append(" ====\n\n");
                    try (java.io.InputStream in = getAssets().open("licenses/" + n)) {
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
                        all.append(b.toString("UTF-8")).append("\n\n");
                    }
                }
            }
        } catch (java.io.IOException e) {
            all.append(e);
        }
        TextView t = new TextView(this);
        t.setText(all);
        t.setTextIsSelectable(true);
        t.setTextSize(12);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.xd_about_licenses).setView(sv)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private void showAboutSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        // SetAbout.dc.html: the logo at the top, the name and version under it
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        brand.setPadding(0, dp(8), 0, dp(18));
        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(Style.rounded(this, 0xFF11151C, 16));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.xdesk_ic_fg);
        logo.addView(mark, new FrameLayout.LayoutParams(dp(72), dp(72)));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = Style.label(this, "XDesk", Style.TEXT, 22);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(8);
        brand.addView(name, np);
        brand.addView(Style.label(this, versionName(), Style.TEXT_DIM, Style.TEXT_SMALL));
        c.addView(brand);
        // Website, GitHub and the changelog open in the browser
        LinearLayout links = s.card(c);
        s.linkRow(links, getString(R.string.xd_about_website), null, "", () -> openLink("https://n2ray.github.io/xdesk/"));
        s.linkRow(links, getString(R.string.xd_about_github), null, "", () -> openLink("https://github.com/n2ray/xdesk"));
        s.linkRow(links, getString(R.string.xd_about_changelog), null, "",
            () -> openLink("https://github.com/n2ray/xdesk/releases"));
        s.linkRow(links, getString(R.string.xd_about_licenses), null, "", this::showLicenses);
        LinearLayout about = s.card(c);
        ((LinearLayout.LayoutParams) about.getLayoutParams()).topMargin = dp(16);
        s.switchRow(about, getString(R.string.xd_diag_setting), getString(R.string.xd_diag_setting_note), prefs.getBoolean(PREF_DIAG, false), on -> {
            prefs.edit().putBoolean(PREF_DIAG, on).apply();
            applyDiagVisibility();
        });
        final ShellActivation shell = apps.shell();
        if (shell.isPaired() || shell.isRunning()) {
            // SetAbout's 内置注入服务: its state with the green dot, pairing again, stopping it
            LinearLayout builtin = s.section(c, getString(R.string.xd_builtin));
            s.dotRow(builtin, shell.isRunning(), shellState(shell), getString(R.string.xd_builtin_note));
            s.linkRow(builtin, getString(R.string.xd_pair_again), getString(R.string.xd_pair_again_note), "", () ->
                s.confirm(getString(R.string.xd_pair_again_title), getString(R.string.xd_pair_again_message), getString(R.string.xd_pair_again), () -> {
                    closeSettings();
                    stopShellServer(() -> {
                        shell.forgetPairing();
                        retryRequested = true;
                        activationFlow = true;
                        showActivation(null);
                    });
                }));
            // Stopping it is a stop, not a removal: yellow, in the same card, and asked first
            if (shell.isRunning()) {
                s.stopRowIn(builtin, getString(R.string.xd_builtin_stop), getString(R.string.xd_builtin_stop_note), () ->
                    s.confirm(getString(R.string.xd_builtin_stop_title), getString(R.string.xd_builtin_stop_message),
                        getString(R.string.xd_stop), SettingsUi.LEVEL_STOP, () -> {
                            closeSettings();
                            stopShellServer(null);
                        }));
            }
        }
        TextView credit = Style.label(this, getString(R.string.xd_about_credit), Style.TEXT_DIM, Style.TEXT_SMALL);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(dp(6), dp(16), dp(6), dp(4));
        c.addView(credit);
        settingsPage(getString(R.string.xd_section_about), c, null);
    }

    private String shellState(ShellActivation shell) {
        if (shell.isRunning()) return getString(R.string.xd_builtin_running);
        if (shell.isStarting()) return getString(R.string.xd_builtin_starting);
        return getString(shell.isPaired() ? R.string.xd_builtin_not_running : R.string.xd_builtin_not_paired);
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
            setTarget(null);
            router.cancelAll();
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

    /** SetAbout's links: the browser is the only place they open. */
    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Log.w(TAG, "no browser for " + url, e);
        }
    }

    // --- 物理键鼠 ---

    /** An external mouse or a real keyboard on the phone (Bluetooth, USB). */
    private boolean hasExternalInput() {
        for (int id : inputs.getInputDeviceIds()) {
            InputDevice d = inputs.getInputDevice(id);
            if (d == null || !d.isExternal() || d.isVirtual()) continue;
            // XDesk's own keyboard (UhidKeyboard, for a Mac's or a browser's typing into Android apps) looks like a
            // physical one to Android: it is not one the phone could give away
            if (UhidKeyboard.NAME.equals(d.getName())) continue;
            if (d.supportsSource(InputDevice.SOURCE_MOUSE) || d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
        }
        return false;
    }

    private boolean hasExternalMouse() {
        for (int id : inputs.getInputDeviceIds()) {
            InputDevice d = inputs.getInputDevice(id);
            if (d != null && d.isExternal() && d.supportsSource(InputDevice.SOURCE_MOUSE)) return true;
        }
        return false;
    }

    /** Last finger on the phone's screen: a search line just touched is the user's, not to be taken for the mouse. */
    private long lastFingerDown;

    private boolean phoneTyping() {
        View f = getCurrentFocus();
        if (!(f instanceof android.widget.EditText)) return false;
        WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
        boolean ime = in != null && in.isVisible(WindowInsets.Type.ime());
        return ime || android.os.SystemClock.uptimeMillis() - lastFingerDown < 3000;
    }

    private void updateCapture() {
        if (keyView == null) return;
        boolean home = updateHome();
        syncDesktopKeyboard();
        // Everything that changes what is on the external screen ends up here: the Dock follows it
        updateExternalDock();
        applyCapture(home);
    }

    /** What had the input when the split-stage prototype took it over (a Mac content, the X desktop): it gets it back. */
    private InputSink beforeSplit;

    /** The split-stage prototype (DISPLAY.md §15) opened or closed: give it the input, or give the input back. */
    private void splitStageChanged() {
        if (SplitStageActivity.isActive()) {
            // The Launchpad's home comes back by itself after the stage; a content's sink would not
            if (client != SplitStageActivity.input && client != SwitcherOverlay.INPUT && beforeSplit == null) beforeSplit = client;
            updateStatus();
            updateCapture();
            return;
        }
        if (client == SplitStageActivity.input || beforeSwitcher == SplitStageActivity.input) {
            if (client == SplitStageActivity.input) client = beforeSplit;
            if (beforeSwitcher == SplitStageActivity.input) beforeSwitcher = beforeSplit;
            beforeSplit = null;
            // Whatever the stage held (a key, a button) must not stay down on it or keep its device bound there
            keysIn.cancel();
            mouseIn.cancel();
            heldModifiers.clear();
        }
        updateStatus();
        updateCapture();
    }

    /** The mouse captured or let go as things are now. @param home the external screen's home has the input */
    private void applyCapture(boolean home) {
        syncKeyMode();
        if (hasWindowFocus()) {
            // 只有 Termux 桌面和 Mac 这些内容才抓取。选择界面要用鼠标点，安卓应用的键鼠由注入服务接管，没接管时就该留给手机。
            // 外接屏上什么都没有时，鼠标归外接屏的主屏（启动台），手机上的选择界面用手指点
            // With an external screen the mouse belongs there even while the phone shows its list (fingers work that)
            boolean external = externalDisplay() != null;
            // The Launchpad draws its own pointer, moved only through INPUT: a mouse left to Android moves nothing there
            boolean launchpad = client == SwitcherOverlay.INPUT && SwitcherOverlay.isShown() && hasExternalMouse();
            // A content still opening (a Mac connecting) counts: the mouse stays off the phone until it is there
            // The home without a mouse is worked by the phone's touchpad: nothing to capture, and nothing is
            boolean want = home && hasExternalMouse() || launchpad || !apps.isActive() && (session.isActive() || contentKey != null || pendingContent != null) && !desktopTaken && phoneForwards()
                && (external || !launcherShown()) && hasExternalMouse()
                // The split-stage prototype (DISPLAY.md §15) takes the captured mouse like X and Mac content
                || SplitStageActivity.isActive() && !apps.isActive() && !desktopTaken && phoneForwards() && external && hasExternalMouse();
            // Capture goes only to the focused view: the phone's list keeps the focus on its search line, and a capture
            // asked for then is dropped (the keys still went on, the mouse did not). Not while the user types there.
            if (want && !keyView.isFocused() && !phoneTyping()) keyView.requestFocus();
            if (want) keyView.requestPointerCapture();
            else keyView.releasePointerCapture();
        }
        updateDiag();
    }

    /**
     * The external screen's home (DEVICES.md §4): nothing on it (the dashboard), so the switchboard is what it shows,
     * and the mouse (if the phone has one), the keyboard and the phone's touchpad go to it there, with its own
     * pointer. With or without a mouse: the touchpad reaches it as the `client` like any target. Not on the car's
     * display, and not without the permission to draw over apps (the dashboard says so). Shown or taken away here.
     * @return whether the home has the input now
     */
    private boolean updateHome() {
        Display d = externalDisplay();
        // A Linux device connected apps-only with no program on the screen yet: only the bare X background, so the home too
        // By what XDesk itself started or switched to (desktopName): runningSession is xdesk status as last read, which
        // still said "+apps" after 整个桌面 was chosen, and the home then covered the whole desktop
        boolean idleX = session.isActive() && desktopName != null && desktopName.endsWith(Devices.APPS_ONLY);
        boolean want = d != null && !TargetDisplay.isCar(d) && !apps.isActive() && (!session.isActive() || idleX) && contentKey == null
            && pendingContent == null && !desktopTaken && phoneForwards() && !SplitStageActivity.isActive();
        if (!want) {
            if (SwitcherOverlay.isHome()) SwitcherOverlay.hide(switcherHost);
            return false;
        }
        // The Mac password card has the input: the home waits under it
        if (MacLogin.isShown()) return false;
        if (SwitcherOverlay.isShown()) return client == SwitcherOverlay.INPUT;
        if (!SwitcherOverlay.showHome(this, d, switcherHost)) return false;
        MacSource.userLooking();
        beforeSwitcher = client;
        client = SwitcherOverlay.INPUT;
        return true;
    }

    /** The soft keyboard was asked for (toggleKeyboard) and has not opened or closed since. */
    private boolean softKeysAsked;

    /**
     * INPUT.md §5.2, a Mac that composes itself: the target is a Mac set to its own input method, a physical keyboard
     * is on the phone, and the phone's soft keyboard is neither open nor asked for (that one types through the
     * phone's input method, as before). The Launchpad's search is not a Mac: it keeps the phone's text.
     */
    private boolean macRawKeys() {
        InputSink c = client;
        return c instanceof MacInput && ((MacInput) c).rawKeys() && !keyboardOpen && !softKeysAsked
            && ImePlacement.hasPhysicalKeyboard();
    }

    /**
     * INPUT.md §5.2, the same for X: the target is the X desktop or a Linux window, an input method runs on the X
     * side (xdesk status, read at the moments parseTermuxState runs), the Linux device shown is not set to the
     * phone's input method, a physical keyboard is on the phone and the soft keyboard is neither open nor asked for.
     * The keys then go to X as the scan codes they always were (DesktopInput.key), letters too, in X's own layout.
     */
    private boolean xRawKeys() {
        InputSink c = client;
        if (c == null || (c != session.input() && !(c instanceof WindowScopedInput))) return false;
        if (keyboardOpen || softKeysAsked) return false;
        Devices dv = Devices.get(this);
        if (!dv.xIme() || !ImePlacement.hasPhysicalKeyboard()) return false;
        // The device of what is shown: a Linux program's environment, else the one whose desktop runs. Its own
        // keyboard choice, else the global setting (which keyModeFollows keeps in step)
        String key = contentKey;
        return !dv.linuxPhoneIme(key != null ? dv.deviceOf(key) : dv.deviceOfSession(runningSession),
            prefs.getBoolean(PREF_KEYS_VIA_IME, true));
    }

    /** The target composes what a physical keyboard types: a Mac or the X side with its own input method. */
    private boolean rawKeys() {
        return client == MacLogin.INPUT || macRawKeys() || xRawKeys();
    }

    /** The input connection follows rawKeys(). Cheap when nothing changed; called wherever one of its parts may have. */
    private InputSink capturedDesktop;
    private IMacKeyboard capturedKeys;

    private void syncDesktopKeyboard() {
        // WindowScopedInput belongs to the single-window switcher. Only the bare X desktop owns Alt+Tab.
        boolean linuxDesktop = client instanceof DesktopInput && client == session.input();
        boolean keyboardFocus = hasWindowFocus() || (linuxDesktop && desktopTaken);
        InputSink next = activityStarted && keyboardFocus && (linuxDesktop || client instanceof ScreenShareInput)
                && !apps.isActive() && ownership.forwarded() && !phoneTyping() ? client : null;
        if (capturedDesktop == next) return;
        if (capturedDesktop != null) {
            keysIn.cancel();
            if (capturedDesktop instanceof ScreenShareInput) ((ScreenShareInput) capturedDesktop).releaseAll();
            heldModifiers.clear();
        }
        capturedDesktop = next;
        capturedKeys = next == null ? null : new IMacKeyboard.Stub() {
            private final java.util.HashSet<Integer> modifiers = new java.util.HashSet<>();
            private final java.util.HashSet<Integer> shortcutKeys = new java.util.HashSet<>();
            @Override public void reset() { ui.post(() -> {
                if (capturedKeys != this) return;
                keysIn.cancel();
                if (next instanceof ScreenShareInput) ((ScreenShareInput) next).releaseAll();
                modifiers.clear();
                shortcutKeys.clear();
                heldModifiers.clear();
            }); }
            @Override public void key(String device, int scan, int value) { ui.post(() -> {
                if (capturedKeys != this) return;
                InputDevice keyboard = null;
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice d = InputDevice.getDevice(id);
                    if (d != null && d.isExternal() && device.equals(d.getName())) { keyboard = d; break; }
                }
                if (keyboard == null) return;
                if (InputInjector.metaOf(scan) != 0) {
                    if (value == 0) modifiers.remove(scan); else modifiers.add(scan);
                }
                int meta = 0;
                for (int held : modifiers) meta |= InputInjector.metaOf(held);
                int code = InputInjector.KEYS.getOrDefault(scan, KeyEvent.KEYCODE_UNKNOWN);
                if (android.os.Build.VERSION.SDK_INT >= 33 && scan != 43 && code != KeyEvent.KEYCODE_UNKNOWN) {
                    int mapped = keyboard.getKeyCodeForKeyLocation(code);
                    if (mapped != KeyEvent.KEYCODE_UNKNOWN) code = mapped;
                }
                long now = android.os.SystemClock.uptimeMillis();
                KeyEvent event = new KeyEvent(now, now, value == 0 ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN,
                        code, value == 2 ? 1 : 0, meta, keyboard.getId(), scan, 0, InputDevice.SOURCE_KEYBOARD);
                // Send ordinary typing through the focused window and its input method, as before capture.
                // Keep command chords out of the IME, including their releases if the modifier lifted first.
                if (value == 1 && (event.isCtrlPressed() || event.isMetaPressed()
                        || (meta & KeyEvent.META_ALT_LEFT_ON) != 0
                        || scan == 15 && event.isAltPressed()
                        || scan == 29 || scan == 97 || scan == 125 || scan == 126 || scan == 56)) {
                    shortcutKeys.add(scan);
                }
                boolean shortcut = value == 0 ? shortcutKeys.remove(scan) : shortcutKeys.contains(scan);
                if (shortcut || (linuxDesktop && desktopTaken)) physicalKey(event, false);
                else getWindow().injectInputEvent(event);
            }); }
        };
        apps.macKeyboard(capturedKeys);
    }

    private void syncKeyMode() {
        syncDesktopKeyboard();
        if (keyView == null) return;
        boolean was = keyView.bridge().raw(), raw = rawKeys();
        keyView.bridge().setRaw(raw);
        // Raw keys ending (a Linux desktop stopped, a Mac went) restarts the connection, and fcitx5 then brought its
        // soft keyboard up on the phone with nobody asking (owner, 23 November): not asked for, it goes again
        if (was && !raw && !softKeysAsked && !keyboardOpen) {
            ui.postDelayed(() -> {
                if (!softKeysAsked && keyView != null) ime.hideSoftInputFromWindow(keyView.getWindowToken(), 0);
            }, 150);
        }
    }

    /** Devices changed: the X side's input method came or went, or a Linux device's keyboard switch was flipped. */
    private final Runnable keyModeFollows = this::syncKeyMode;

    /** 外接键盘直接转扫描码，手机自身的按键（返回、音量）不动。输入法没接走的才会到这里。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        keyEvents++;
        updateDiag();
        return physicalKey(e, false) || super.dispatchKeyEvent(e);
    }

    /**
     * A key of a keyboard or mouse attached to the phone, on its way to the target (INPUT.md §5.1). It gets here when
     * the phone's input method did not want it: the method let it pass and Android brought it to dispatchKeyEvent, or
     * the method took it and handed it back through its input connection (fcitx5 does that with every key of a
     * physical keyboard; TextBridge passes those on untouched, fromIme).
     * @return false when it is not a key to forward: the phone's own handling goes on
     */
    private boolean physicalKey(KeyEvent e, boolean fromIme) {
        InputDevice d = e.getDevice();
        if (d == null || !d.isExternal()) return false;
        // The Mac password card on the external screen takes every key while it is up (MacLogin.key)
        if (MacLogin.key(e)) return true;
        // Ctrl+Alt+M on the phone's window: take the desktop over (giving it back is in the desktop window, see onCreate)
        if (ownership.onKey(e)) {
            updateStatus();
            return true;
        }
        // Ctrl+Alt+Q with a Mac on the external screen: cycle its quick quality mode (INPUT.md §7)
        if (qualityKey(e)) return true;
        // Ctrl+Alt+N: the notification centre on the external screen (INPUT.md §7, NOTIFY.md §4.1)
        if (notify != null && notify.hotkey(e)) return true;
        // Ctrl+Alt+N with no notification access: the centre is not there, so the phone says how to get it
        if (notifyAccessKey(e)) return true;
        // Ctrl+Alt+Space with a single Mac window or app on the screen: its menu bar (INPUT.md §7, MACMENU.md §3)
        if (menus != null && menus.hotkey(e)) return true;
        // The Launchpad takes the keys whatever is under it. An Android app has the keyboard by itself (its display
        // has the focus), so nothing is passed to it from here
        boolean forwarding = client != null && (client == SwitcherOverlay.INPUT || !apps.isActive()) && phoneForwards();
        // Where a physical key went (never which key: a Mac's password is typed this way)
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)
            Log.d(TAG, "key: forwarding " + forwarding + (fromIme ? " (back from the input method)" : "") + ", client "
                + sinkName(client) + ", bound " + sinkName(keysIn.boundTo())
                + ", apps " + apps.isActive() + ", phoneForwards " + phoneForwards() + ", content " + contentKey);
        if (!forwarding) return releaseHeld(e);
        syncKeyMode();
        // The Launchpad's search takes what the input method made of the key, text in the keyboard's layout: the
        // bridge goes on with it. (Keys that came straight here are keys to it, on a US layout.)
        if (fromIme && client == SwitcherOverlay.INPUT) return releaseHeld(e);
        // The notification centre's keys while it is open (NOTIFY.md §4): the arrows, Enter, Delete, R, Esc, and
        // text into an open reply field only
        if (notify != null && notify.key(e)) return true;
        // The Mac menu bar's keys while it is open (MACMENU.md §4): ←→ ↑↓ Enter → Esc
        if (menus != null && menus.key(e)) return true;
        // Esc closes the Dock opened over this content, and is not the content's then
        if (dockPointer.key(e.getScanCode(), e.getAction() == KeyEvent.ACTION_DOWN)) return true;
        if (mouseSideKey(e)) return true;
        int scan = e.getScanCode();
        // Media and volume keys work the phone, which plays the sound or sends it on (INPUT.md §5.5)
        if (scan <= 0 || mediaKey(e.getKeyCode())) return false;
        if (!io.github.n2ray.xdesk.input.HeldModifiers.isModifier(scan)) heldModifiers.check(e.getMetaState(), releaseModifier);
        if (physicalText(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            // Only what went down here goes up: an input method that kept a press for itself (it was composing)
            // still lets the release through, and the target never saw that key
            if (keysIn.holdsKey(scan)) keysIn.key(scan, false);
            heldModifiers.sent(scan, false);
        } else if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                keysIn.key(scan, true);
                heldModifiers.sent(scan, true);
            } else if (keysIn.holdsKey(scan) && keysIn.takesRepeats()) {
                // A Mac that does not repeat by itself gets Android's repeats (INPUT.md §5.4: the target end repeats)
                keysIn.key(scan, true);
            }
        }
        return true;
    }

    /** For the log: what kind of target this is. */
    private String sinkName(InputSink s) {
        if (s == null) return "none";
        if (s == SwitcherOverlay.INPUT) return "Launchpad";
        if (s == MacLogin.INPUT) return "MacPassword";
        if (s == apps.input()) return "AndroidApp";
        return s.getClass().getSimpleName();
    }

    /**
     * Keys are not forwarded (any more): the target went away, an Android app took the screen, Ctrl+Alt+M gave the
     * keyboard back. A release of what went down on a target before that still goes there (INPUT.md §0.5). Left
     * out, the key stayed down on the target, and worse, its device stayed bound to that target (the Router sends
     * everything of a device where its held keys went): after Enter in the Launchpad opened a Mac, the release came
     * while nothing was forwarded yet, and every later key and text went to the closed Launchpad instead of the Mac.
     */
    private boolean releaseHeld(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_UP) return false;
        int button = mouseSideButton(e);
        if (button != 0) {
            if (!mouseIn.holdsButton(button)) return false;
            mouseIn.button(button, false);
            return true;
        }
        if (textKeys.get(e.getKeyCode())) {
            textKeys.delete(e.getKeyCode());
            return true;
        }
        int scan = e.getScanCode();
        if (scan <= 0 || !keysIn.holdsKey(scan)) return false;
        keysIn.key(scan, false);
        heldModifiers.sent(scan, false);
        return true;
    }

    /** Physical modifiers sent as held, checked against Android's meta state (an input method may keep a release). */
    private final io.github.n2ray.xdesk.input.HeldModifiers heldModifiers = new io.github.n2ray.xdesk.input.HeldModifiers();

    /** The Q of the hotkey went down here: its release is swallowed too, wherever the focus is by then. */
    private boolean swallowQuality;

    /**
     * Ctrl+Alt+Q with a Mac on the external screen: cycle its quick quality mode (清晰 → 平衡 → 流畅, INPUT.md §7)
     * and flash the new one over the picture. Not on a Mac, it is just a key, like any other. Q was not taken by
     * anything (INPUT.md §5.2: XDesk takes only Ctrl+Alt+M, Ctrl+Alt+Q and Ctrl+Alt+N).
     */
    private boolean qualityKey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_Q) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && swallowQuality) {
            swallowQuality = false;
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0 || !e.isCtrlPressed() || !e.isAltPressed()) return false;
        MacClient client = macOnScreen();
        if (client == null) return false;
        swallowQuality = true;
        cycleQuality(client);
        return true;
    }

    /** The side panel's switches (NOTIFY.md §4): what they do here. */
    private final Notify.Actions sideActions = new Notify.Actions() {
        @Override public void launchpad() { showSwitcher(false); }
        @Override public void keysToPhone() {
            ownership.toggle();
            updateStatus();
        }
        @Override public boolean soundOnPhone() { return !audioRoute.selectedKey().isEmpty(); }
        @Override public void toggleSound() {
            // The screen's sound is the system default (it follows the display); the phone's is its speaker
            audioRoute.select(soundOnPhone() ? AudioRoute.KEY_SYSTEM : android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + "|");
        }
        @Override public Boolean gameMode() { return apps.isActive() && apps.isForwarding() ? apps.isGameMode() : null; }
        @Override public void toggleGameMode() { apps.setGameMode(!apps.isGameMode()); }
        @Override public String macQuality() {
            MacClient client = macOnScreen();
            if (client == null) return null;
            HostSettings h = client.settings();
            if (h == null) h = HostSettings.saved(MacStore.stream(XDeskActivity.this, client.mac().id).host);
            if (h == null) return null;
            // Settings none of the three modes have (chosen one by one on the Picture page): -1, said as custom
            int mode = AutoQuality.modeOf(h.bitrate, h.captureScale, h.prioritizeSpeed);
            String[] names = getResources().getStringArray(R.array.xd_mac_mode_names);
            return mode >= 0 && mode < names.length ? names[mode] : getString(R.string.xd_mac_quality_custom);
        }
        @Override public void nextMacQuality() {
            MacClient client = macOnScreen();
            if (client != null) cycleQuality(client);
        }
    };

    /** The Mac whose content is on the external screen now, or null. */
    private MacClient macOnScreen() {
        if (contentKey == null || !contentKey.startsWith(MacSource.PREFIX)) return null;
        int end = contentKey.indexOf(':', MacSource.PREFIX.length());
        return MacClients.get(this, contentKey.substring(MacSource.PREFIX.length(), end >= 0 ? end : contentKey.length()));
    }

    /** One step of the quick quality cycle, with the new mode over the picture. */
    private void cycleQuality(MacClient client) {
        HostSettings h = client.settings();
        if (h == null) h = HostSettings.saved(MacStore.stream(this, client.mac().id).host);
        if (h == null) return;
        int at = AutoQuality.modeOf(h.bitrate, h.captureScale, h.prioritizeSpeed);
        int next = at == AutoQuality.SHARP ? AutoQuality.BALANCED : at == AutoQuality.BALANCED ? AutoQuality.SMOOTH : AutoQuality.SHARP;
        if (MacModeRow.apply(this, client, next)) {
            String[] names = getResources().getStringArray(R.array.xd_mac_mode_names);
            client.flash(getString(R.string.xd_mac_mode_flash, names[next]));
        }
    }

    private final io.github.n2ray.xdesk.input.HeldModifiers.Out releaseModifier = scan -> keysIn.key(scan, false);

    /**
     * A mouse's back and forward buttons: Android sends them as KEYCODE_BACK and KEYCODE_FORWARD from the mouse (no
     * scan code) besides the button state, and the captured mouse leaves them to this one way. They go on as X
     * buttons 8 and 9, which each target turns into its Back (⌘[ on a Mac). Unhandled they would be the phone's Back
     * and close XDesk's window.
     */
    private boolean mouseSideKey(KeyEvent e) {
        int button = mouseSideButton(e);
        if (button == 0) return false;
        if (e.getRepeatCount() == 0 && (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP)) {
            // A cancelled up (focus moved) still lets go
            mouseIn.button(button, e.getAction() == KeyEvent.ACTION_DOWN && !e.isCanceled());
        }
        return true;
    }

    /** The X button (8 back, 9 forward) a mouse's side button key stands for, 0 for any other key. */
    private static int mouseSideButton(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_FORWARD) return 0;
        InputDevice d = e.getDevice();
        boolean mouse = e.getScanCode() == 0 && d != null && (d.supportsSource(InputDevice.SOURCE_MOUSE)
            || d.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE));
        if (!mouse) return 0;
        return code == KeyEvent.KEYCODE_BACK ? io.github.n2ray.xdesk.input.Input.BACK_BUTTON : io.github.n2ray.xdesk.input.Input.FORWARD_BUTTON;
    }

    private static boolean mediaKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_HEADSETHOOK:
                return true;
            default:
                return false;
        }
    }

    /** Keys of the physical keyboard that went out as text, by key code: their ups and repeats stay text too. */
    private final android.util.SparseBooleanArray textKeys = new android.util.SparseBooleanArray();
    /** A dead key (an accent) waiting for the letter it goes on. 0 when none. */
    private int deadAccent;
    private static final int SCAN_RIGHT_ALT = 100;

    /**
     * INPUT.md §5.1 for a target that is not Android: a key the phone's input method let through, that types a visible
     * character (no Ctrl, Alt or Meta; AltGr is a layout's own), goes out as text in the keyboard's layout, through
     * the text bridge. Everything else (shortcuts, F keys, arrows, Esc, Tab, Enter, Backspace, modifiers) stays a key.
     * Keys the input method wanted never get here: it composes on the phone and commits through the bridge.
     * Off in the settings, or while XDesk's switcher has the keys: every key is a key, as in 0.5.1.
     */
    private boolean physicalText(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (textKeys.get(code)) {
                textKeys.delete(code);
                return true;
            }
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (textKeys.get(code)) {
            if (e.getRepeatCount() > 0) {
                // Held: the target cannot repeat text, so each of Android's repeats types it again
                if (deadAccent == 0) typeChar(e);
                return true;
            }
            // A new press of a key whose release never came here (the window lost the focus under it): judged
            // afresh, not swallowed as the rest of the old press
            textKeys.delete(code);
        }
        // A Mac or an X side that composes itself gets the letters as keys too (rawKeys)
        if (e.getRepeatCount() > 0 || !prefs.getBoolean(PREF_KEYS_VIA_IME, true) || client == SwitcherOverlay.INPUT || rawKeys()) return false;
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_TAB) return false;
        boolean altGr = (e.getMetaState() & KeyEvent.META_ALT_RIGHT_ON) != 0;
        if (e.isCtrlPressed() || e.isMetaPressed() || (e.isAltPressed() && !altGr) || keySink.latched()) return false;
        int u = e.getUnicodeChar();
        if (u == 0) return false;
        if ((u & KeyCharacterMap.COMBINING_ACCENT) == 0 && (u < 0x20 || u == 0x7f)) return false;
        textKeys.put(code, true);
        typeChar(e);
        return true;
    }

    private void typeChar(KeyEvent e) {
        int u = e.getUnicodeChar();
        if ((u & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            deadAccent = u & KeyCharacterMap.COMBINING_ACCENT_MASK;
            return;
        }
        String s;
        if (deadAccent != 0) {
            int composed = KeyCharacterMap.getDeadChar(deadAccent, u);
            s = composed != 0 ? new String(Character.toChars(composed))
                : new String(Character.toChars(deadAccent)) + new String(Character.toChars(u));
            deadAccent = 0;
        } else {
            s = new String(Character.toChars(u));
        }
        // AltGr is part of the layout: the target must not see it held over this text (it would be ⌥ on a Mac)
        boolean altGr = (e.getMetaState() & KeyEvent.META_ALT_RIGHT_ON) != 0;
        if (altGr) keysIn.key(SCAN_RIGHT_ALT, false);
        keyView.bridge().type(s);
        if (altGr) keysIn.key(SCAN_RIGHT_ALT, true);
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
        if (!dimmed) bumpIdle();
        audioRoute.setCar(carConnected());
        if (!session.isActive() || !desktopOnCar) {
            updateChooserDisplay();
            return;
        }
        if (car == null) {
            // 和拔掉外接屏一样：X 和桌面继续运行
            setStatus(Style.WARN, getString(R.string.xd_car_gone_status));
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
            else showSetup(getString(R.string.xd_starting), getString(R.string.xd_connecting_injector), true, null, null);
        } else {
            showChooser();
        }
    }

    // --- 选择界面 ---

    private void showChooser() {
        // The Launchpad mode's home is the grid; the console's is this list
        if (launchpadMode()) {
            showLaunchpad();
            return;
        }
        showChooserPage();
    }

    /** 不停会话，把完整列表盖在触摸板上，用来切换到不在 Dock 里的东西。 */
    private void openChooserOverSession() {
        if (launchpadMode()) {
            showLaunchpad();
            return;
        }
        showChooserPage();
    }

    /** The console mode's list: showChooser and openChooserOverSession draw the same page. */
    private void showChooserPage() {
        MacSource.userLooking();  // the Mac rows should be fresh: connect paired Macs now
        setupScreen.setVisibility(View.GONE);
        chooser.setVisibility(View.VISIBLE);
        if (launchpad != null) launchpad.setVisibility(View.GONE);
        launchpadPage = false;
        updateChooserTop();
        applyFullscreen();
        // With an external screen the mouse stays there (its home, the desktop, a Mac) while fingers work this list;
        // without, it is let go
        updateCapture();
        updateAudioChip();
        refreshChooser();
    }

    /**
     * The Launchpad mode's home, the grid (LaunchpadView), as showChooser is the console's list. The session, if
     * there is one, is not touched: the grid is the phone's page over it.
     */
    private void showLaunchpad() {
        if (launchpad == null) {
            showChooserPage();
            return;
        }
        MacSource.userLooking();
        setupScreen.setVisibility(View.GONE);
        if (chooser.getVisibility() == View.VISIBLE) ime.hideSoftInputFromWindow(chooser.getWindowToken(), 0);
        chooser.setVisibility(View.GONE);
        chooserTyping = false;
        ui.removeCallbacks(searchFocus);
        if (launchpad.getVisibility() == View.VISIBLE) {
            // Back from the search page: the keyboard goes and the grid is the whole tab again
            ime.hideSoftInputFromWindow(launchpad.getWindowToken(), 0);
            launchpad.clearSearch();
        }
        launchpadPage = true;
        launchpad.setVisibility(View.VISIBLE);
        updateChooserTop();
        applyFullscreen();
        updateCapture();
        updateAudioChip();
        refreshChooser();
        // The grid is worked by finger: beside a mouse it takes no focus (updatePhoneFocus), so a tap leaves the
        // external screen's keyboard where it was
        updatePhoneFocus();
        if (phonePassive) refocusExternal();
    }

    private void closeChooserOverSession() {
        showRunning();
    }

    /**
     * The list's Touchpad button: there whenever the touchpad page has a use. While something runs; and whenever an
     * external screen (not the car's) is there, whose home is worked with the touchpad when the phone has no mouse.
     */
    private void updateChooserTop() {
        Display d = externalDisplay();
        chooser.setTouchpadShown(apps.isActive() || session.isActive() || contentKey != null || (d != null && !TargetDisplay.isCar(d)));
        showOwnership();
    }

    // --- the phone's page while something is on the external screen (DEVICES.md §3.1) ---

    // The page the user chose himself (Touchpad, Launcher, Back) needs no state: nothing changes it but the two
    // moments below, something opened (showOpened) and a mouse coming or going (applyDefaultPage)
    /** launcherIsDefault() as it was when the default was last applied. */
    private boolean launcherWasDefault;

    /**
     * With an external screen and a mouse on the phone, the mouse works that screen and the phone is not needed as a
     * touchpad: its page is the list. Without a mouse the phone is the touchpad.
     */
    private boolean launcherIsDefault() {
        return externalDisplay() != null && hasExternalMouse();
    }

    /** Something was opened, or came back, on the external screen: the phone's page by the rule, whatever was chosen by hand before. */
    private void showOpened() {
        launcherWasDefault = launcherIsDefault();
        if (launchpadMode()) {
            // The Launchpad's page is the user's: the grid is its home and something opened on the screen must not
            // take the phone off it (neither the grid nor a touchpad page chosen by hand moves). The setup page for
            // what is still starting is what shows, and this comes back when it is done.
            chooserTyping = false;
            ui.removeCallbacks(searchFocus);
            if (launchpadPage) {
                if (setupScreen.getVisibility() == View.VISIBLE) setupScreen.setVisibility(View.GONE);
                showLaunchpad();
            } else {
                showRunning();
            }
            // The keys and the captured mouse go through this view, also while the grid is what shows
            keyView.requestFocus();
            updateCapture();
            updatePhoneFocus();
            if (phonePassive) refocusExternal();
            return;
        }
        if (!launcherWasDefault) {
            showRunning();
            return;
        }
        chooserTyping = false;
        ui.removeCallbacks(searchFocus);
        if (chooser.getVisibility() != View.VISIBLE || setupScreen.getVisibility() == View.VISIBLE) {
            openChooserOverSession();
        } else {
            // The list stays: what showRunning does for the touchpad page, without leaving
            ime.hideSoftInputFromWindow(chooser.getWindowToken(), 0);
            chooser.clearSearch();
            setLauncherNotice(null);
            displayNoticeShown = false;
            updateChooserTop();
            applyFullscreen();
            refreshDocks();
        }
        // The keys and the captured mouse go through this view, also while the list is what shows
        keyView.requestFocus();
        updateCapture();
    }

    /** A mouse came or went: the default page again, also over a page chosen by hand. Only while something runs. */
    private void applyDefaultPage() {
        if (chooser == null || keyView == null) return;
        // In the Launchpad mode the phone's page is the user's (the grid is the home, the touchpad is one tap away):
        // a mouse coming or going does not move it
        if (launchpadMode()) return;
        boolean now = launcherIsDefault();
        if (now == launcherWasDefault) return;
        launcherWasDefault = now;
        boolean running = (apps.isActive() && apps.isStarted()) || session.isConnected() || contentKey != null;
        // Nothing runs: the list is the page anyway. A setup screen is saying something, the search is being typed into
        if (!running || setupScreen.getVisibility() == View.VISIBLE || phoneTyping()) return;
        boolean list = chooser.getVisibility() == View.VISIBLE;
        if (now && !list) openChooserOverSession();
        else if (!now && list) showRunning();
    }

    @Override public void onBackPressed() {
        if (gamepadShown) {
            if (!gamepadView.back()) hideGamepad();
            return;
        }
        if (padFullscreen) setFullscreenWanted(false);
        else if (launchpadMode()) {
            // Back from the touchpad page goes to the Launchpad grid (LaunchpadTouchpad*.dc.html); from the grid,
            // the phone's home, it leaves the app as usual. The touchpad page is not launchpadPage: showRunning()
            // clears that, so what says "the grid is not the page now" is launcherShown() alone.
            if (!launcherShown() && setupScreen.getVisibility() != View.VISIBLE) showLaunchpad();
            else super.onBackPressed();
        }
        else if (chooser.getVisibility() == View.VISIBLE && (apps.isActive() || session.isActive())) closeChooserOverSession();
        else if (chooser.getVisibility() != View.VISIBLE && setupScreen.getVisibility() != View.VISIBLE
                && !apps.isActive() && !session.isActive() && contentKey == null) {
            // The touchpad page with nothing running (it was working the external home): back to the list, not out
            showChooser();
        } else super.onBackPressed();
    }

    /** 外接屏上现在是什么，和 Dock、最近使用同一种写法。 */
    private String currentKey() {
        if (contentKey != null) return contentKey;
        AppCatalog.App a = apps.app();
        if (a != null) return ChooserView.recentKey(a);
        // An apps-only X with nothing open shows no desktop: nothing is current (not a "termux-xfce+apps" tile)
        if (desktopName != null && desktopName.endsWith(Devices.APPS_ONLY)) return null;
        return session.isActive() && desktopName != null ? ChooserView.recentKey(desktopName) : null;
    }

    /** 上一个应用：现在这个之前用的那个；什么都没在运行时就是最后用的那个。 */
    private String previousKey() {
        return previousKey(currentKey());
    }

    /** 上一个内容（Dock 里第二位）：最近使用里第一个不是 `current` 的；`current` 是现在或刚消失的那一项。 */
    private String previousKey(String current) {
        for (String key : savedRecents()) {
            if (key.equals(current)) continue;
            if (!ChooserView.isAppKey(key) || catalog.find(ChooserView.componentOf(key)) != null) return key;
        }
        return null;
    }

    private void refreshRunningDock() {
        if (runningDock != null) runningDock.refresh();
    }

    /**
     * The favourites, what is on the external screen or the apps changed: every view of the switchboard (the phone's
     * list, the Launchpad, the Docks) draws the model again.
     */
    private void refreshDocks() {
        Switchboard.get(this).changed();
        refreshRunningDock();
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

    /**
     * The card of a launcher item (ItemCard): the same one for every kind of item, from a long press on the phone and
     * from the secondary button on the external Launchpad and Dock. False when the key has none.
     */
    private boolean showItemCard(String key) {
        // The external screen may ask while this activity is on its way out: no window to put a dialog in
        if (isFinishing() || isDestroyed()) return false;
        return ItemCard.show(this, cardHost, key);
    }

    private final ItemCard.Host cardHost = new ItemCard.Host() {
        @Override public String current() { return currentKey(); }
        @Override public boolean onCar() { return XDeskActivity.this.onCar(); }
        @Override public List<String> favourites() { return savedLines(PREF_DOCK); }
        @Override public void toggleFavourite(String key) { chooserHost.onDockToggle(key); }
        @Override public void moveFavourite(String key, int by) { moveInDock(key, by); }
        @Override public void open(String key) {
            // Asked for from the Launchpad, which is still open: it goes as when a tile is opened there
            if (SwitcherOverlay.isShown()) SwitcherOverlay.hide(switcherHost);
            chooseKey(key);
        }
        @Override public AppSession apps() { return apps; }
        @Override public void appInputChanged() { updateStatus(); }
        @Override public void launchpad() { if (!SwitcherOverlay.isShown()) showSwitcher(false); }
    };

    // --- 外接屏上的切换器（DISPLAY.md §6） ---

    /** 切换器开着时输入交给它；关上时还给原来的。 */
    private InputSink beforeSwitcher;

    /**
     * 四指上滑、把手、Dock 打开所有系统的；三指上滑（总览）只列现在这个系统的。
     * 没有外接屏时打开手机上的列表。再调一次就关上。
     */
    void showSwitcher(boolean onlySystem) {
        Display d = externalDisplay();
        // Not on the car: the Launchpad swallows its touches there (no onTouchEvent) until it gets a touch-first switcher (audit P1)
        if (d == null || TargetDisplay.isCar(d)) {
            showChooser();
            return;
        }
        if (SwitcherOverlay.isShown()) {
            SwitcherOverlay.hide(switcherHost);
            return;
        }
        if (!SwitcherOverlay.show(this, d, switcherHost, onlySystem)) {
            // No overlay permission: the phone's list instead, and the input stays where it was
            showChooser();
            return;
        }
        MacSource.userLooking();
        // A latched Ctrl or Alt is held on what was the target: let go there, or the keyboard stays bound to it and
        // the Launchpad's search gets nothing
        releaseLatches();
        if (client != SwitcherOverlay.INPUT) beforeSwitcher = client;
        client = SwitcherOverlay.INPUT;
        // Over an Android app the mouse is the injector's and moves that app's cursor, which was put on the screen
        // before the Launchpad: above it, or the pointer is lost under the scrim
        if (apps.isActive()) apps.raiseCursor();
        updateCapture();
    }

    private static final long HOME_AGAIN_MS = 1500;
    private final Runnable homeAgain = this::updateCapture;
    /**
     * The Launchpad closed over an Android app (one was opened, or nothing was): the mouse captured for the
     * Launchpad is the app's again at once, not when homeAgain comes. Only then: while a content is still opening
     * the capture stays, or Android's own pointer would show up on the phone for that time.
     */
    private final Runnable captureAfterSwitcher = () -> {
        if (keyView != null && apps.isActive() && !SwitcherOverlay.isShown()) applyCapture(false);
    };

    /** What had the input when the Mac password card took it (MacLogin), and whether the soft keyboard was opened for it. */
    private InputSink beforeLogin;
    private boolean loginOpenedKeys;

    private final MacLogin.Host loginHost = new MacLogin.Host() {
        @Override public boolean overAndroidApp() { return apps.isActive() && contentKey == null; }
        @Override public void loginTaken() {
            InputSink c = client;
            if (c == SwitcherOverlay.INPUT) c = beforeSwitcher;
            if (c != MacLogin.INPUT) beforeLogin = c;
            releaseLatches();
            client = MacLogin.INPUT;
            // The phone's connection is a password field now (rawKeys): nothing is learnt or suggested
            syncKeyMode();
            // No keyboard on the phone: its soft keyboard comes up by itself, a password one
            loginOpenedKeys = false;
            if (keyView != null && !ImePlacement.hasPhysicalKeyboard()) {
                WindowInsets w = getWindow().getDecorView().getRootWindowInsets();
                if (w == null || !w.isVisible(WindowInsets.Type.ime())) {
                    loginOpenedKeys = true;
                    toggleKeyboard();
                }
            }
            updateCapture();
            Toast.makeText(XDeskActivity.this, R.string.xd_ss_login_on_screen, Toast.LENGTH_SHORT).show();
        }
        @Override public void loginReleased() {
            if (client == MacLogin.INPUT) client = beforeLogin;
            beforeLogin = null;
            // Leaving the password field forgets what the connection held
            syncKeyMode();
            if (loginOpenedKeys && keyView != null) {
                loginOpenedKeys = false;
                softKeysAsked = false;
                ime.hideSoftInputFromWindow(keyView.getWindowToken(), 0);
            }
            updateCapture();
        }
        @Override public Activity phoneActivity() {
            return isFinishing() || isDestroyed() ? null : XDeskActivity.this;
        }
    };

    private final SwitcherOverlay.Host switcherHost = new SwitcherOverlay.Host() {
        @Override public boolean pointerPlace(float[] out) {
            // The content still has the input while the Launchpad comes: X's or a Mac's pointer is where it starts
            InputSink c = client;
            return c != null && c != SwitcherOverlay.INPUT && !apps.isActive() && c.screenPlace(out);
        }
        @Override public boolean takesKeys() {
            // Over an Android app, and on the home with nothing open: there the external display has no window of
            // its own that takes keys, and once it is the top display (a click there) a keyboard's keys went nowhere
            // (17 November). Over X or a Mac, XDesk's own window has them and hands them over (INPUT)
            return apps.isActive() || contentKey == null && !session.isActive();
        }
        @Override public void pointerLeftAt(float x, float y) {
            // The content the Launchpad was over takes its pointer from there (17 November: it came back where it
            // had been, a jump the other way); over an Android app Android's pointer was the one anyway
            InputSink c = beforeSwitcher;
            if (c != null && c != SwitcherOverlay.INPUT && !apps.isActive()) c.warpTo(x, y);
        }
        @Override public void closed() {
            if (client == SwitcherOverlay.INPUT) client = beforeSwitcher;
            beforeSwitcher = null;
            syncKeyMode();
            // Posted: what was chosen is started right after this, in the same turn
            ui.removeCallbacks(captureAfterSwitcher);
            ui.post(captureAfterSwitcher);
            // Back to the home if nothing took the screen; late enough for what was opened to have begun
            ui.removeCallbacks(homeAgain);
            ui.postDelayed(homeAgain, HOME_AGAIN_MS);
        }
    };

    // --- 窗口交换机：Linux 程序、Mac 窗口这些内容（Contents，DISPLAY.md §1.5） ---

    /** 外接屏上现在的内容（"l:"、"m:" 开头的键）；是安卓应用或整个 Linux 桌面时为 null。 */
    private String contentKey;
    /** 外接屏上的安卓应用的键（recentKey），在 chooseApp 里记下：它结束时 contentGone 要跳过它找上一个。 */
    private String appKey;
    /** The content last chosen and still being opened; a result for any other key is stale (the user moved on). */
    private String pendingContent;
    /** The display the content was opened on. */
    private int contentDisplay = Display.INVALID_DISPLAY;
    /** 等桌面连上以后要做的事（needDesktop），连上就做，失败就报给内容源。 */
    private Runnable desktopThen;
    private ContentSource.Result desktopFailed;
    private String desktopFor;
    /** Which X desktopThen waits for: the built-in Linux's (true) or Termux's (false); null: whichever connects. */
    private Boolean desktopWantBuiltin;

    private void chooseContent(final String key) {
        ContentSource s = Contents.sourceOf(key);
        if (s == null) return;
        Display d = externalDisplay();
        if (d == null) {
            // Nothing is opening: a content left pending here kept the home off the screen that comes later
            needDisplayNotice();
            return;
        }
        phoneKeys = true;
        pendingContent = key;
        pendingApp = null;  // an Android app still waiting for its activation is no longer what was chosen
        rememberRecent(key);
        if (contentKey != null && !contentKey.equals(key)) leaveContent();
        contentDisplay = d.getDisplayId();
        s.open(key, contentDisplay, contentResult);
    }

    /** Something that is not a content was chosen (an Android app, a desktop): any content still opening is stale. */
    private void leaveContentChoice() {
        pendingContent = null;
        desktopThen = null;
        desktopFailed = null;
        if (contentKey != null) leaveContent();
    }

    /** 外接屏换成别的东西之前：告诉原来的内容源它被拿下了（不是退出）。 */
    private void leaveContent() {
        String key = contentKey;
        contentKey = null;
        // X goes on without the window as the content: its sink is the whole desktop's again (INPUT.md §1.1)
        if (client instanceof WindowScopedInput) client = ((WindowScopedInput) client).desktop();
        if (beforeSwitcher instanceof WindowScopedInput) beforeSwitcher = ((WindowScopedInput) beforeSwitcher).desktop();
        ContentSource s = Contents.sourceOf(key);
        if (s != null) s.hidden(key);
        // A Mac taken off the screen still takes input on its connection, and would get the touchpad's clicks with
        // no picture of where they land, until the next thing has started. (A Linux window stays on the screen and
        // its input with it.)
        if (key != null && key.startsWith(MacSource.PREFIX)) setTarget(null);
    }

    /**
     * What the input goes to from now on. While the Launchpad is open it keeps the input, and this is what it hands
     * the input to when it closes: set directly, the Launchpad stayed on the screen with nothing reaching it (an X
     * desktop that stopped or connected under the home, a content that opened or ended under it).
     */
    private void setTarget(InputSink s) {
        if (client == SwitcherOverlay.INPUT && SwitcherOverlay.isShown()) beforeSwitcher = s;
        else client = s;
        syncDesktopKeyboard();
    }

    /** With a content that draws itself (a Mac) on top: the Android app was in front of the X desktop under it, not behind. */
    private boolean appWasInFront;
    private boolean displayGoing;

    /**
     * A content that drew itself over the display (a Mac's screen) ended on its own: what it covered shows again, so
     * XDesk goes back to it, as it does to an app when the desktop window goes (desktopGone). Without this the X
     * desktop or the Android app was on the screen with no input and the phone said nothing ran.
     */
    private void resumeUnderContent() {
        Display d = externalDisplay();
        if (d == null || displayGoing || apps.isActive() || session.isActive() || contentKey != null || pendingContent != null || isFinishing()) return;
        ICmdEntryInterface x = MainActivity.verifiedServer;
        boolean desktop = MainActivity.getInstance("") != null && x != null && x.asBinder().isBinderAlive();
        if ((appWasInFront || !desktop) && apps.resumeApp(d, prefs.getFloat(PREF_MOUSE_SPEED, 1f), prefs.getBoolean(PREF_ALWAYS_CURSOR, false))) {
            appKey = ChooserView.recentKey(apps.app());  // the app on the screen again, for contentGone
            showSetup(getString(R.string.xd_returning_to, apps.label()), "", true, null, null);
            return;
        }
        if (!desktop) return;
        if (runningSession != null) {
            reattach();
        } else {
            attachDisplay(d);
            showWaiting();
        }
    }

    private final ContentSource.Result contentResult = new ContentSource.Result() {
        @Override public void shown(String key, InputSink input) {
            if (!key.equals(pendingContent)) {
                // Chosen, then something else was chosen while it opened: take it off again
                ContentSource s = Contents.sourceOf(key);
                if (s != null) s.hidden(key);
                return;
            }
            pendingContent = null;
            contentKey = key;
            // A Linux program alone on the screen is a window (WindowScopedInput), not the whole desktop
            setTarget(input != null ? input : new WindowScopedInput(session.input()));
            // A Mac's sound goes where XDesk's sound setting says only through the system route the injector sets:
            // with a monitor on HDMI the system ignored a track's own preferred device (2026-11-19: "the phone"
            // chosen, the Mac still played on the monitor). The Linux desktop connects it the same way
            if (input instanceof MacInput) apps.connectForAudio();
            showOpened();
            ui.removeCallbacks(regainFocus);
            regainTries = 3;
            ui.postDelayed(regainFocus, REGAIN_FIRST_MS);
            updateStatus();
            refreshDocks();
            bumpIdle();
        }
        @Override public void failed(String key, String reason) {
            if (!key.equals(pendingContent)) return;
            pendingContent = null;
            Toast.makeText(XDeskActivity.this, reason, Toast.LENGTH_LONG).show();
            refreshDocks();
            // Nothing took the screen after all: the external home again
            updateCapture();
        }
    };

    private final Contents.Host contentsHost = new Contents.Host() {
        @Override public int targetDisplayId() {
            Display d = externalDisplay();
            return d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        }
        @Override public void needDesktop(Runnable then, ContentSource.Result failed, String key) {
            // Each environment's programs open on its X: the built-in Linux's on its own, Termux's (and its proot
            // and chroot systems') on Termux's. Both may run; the window shows one (XServers)
            boolean builtin = Devices.get(XDeskActivity.this).deviceOf(key).equals(Devices.LINUX + Devices.BUILTIN);
            boolean shownBuiltin = XServers.isBuiltin(XServers.shown());
            boolean connected = session.isActive() && session.isConnected();
            if (connected && shownBuiltin == builtin) {
                then.run();
                return;
            }
            desktopThen = then;
            desktopFailed = failed;
            desktopFor = key;
            desktopWantBuiltin = builtin;
            if (builtin) {
                needBuiltinX();
                return;
            }
            if (connected && shownBuiltin) {
                // A Termux program while the built-in Linux shows: Termux's X if it runs, else its "apps only" X,
                // which comes as a new X server and is shown (xServers.started)
                IBinder termux = termuxX();
                if (termux != null) {
                    XServers.switchTo(termux);
                } else {
                    Devices dv = Devices.get(XDeskActivity.this);
                    String device = dv.deviceOf(key);
                    launchDesktop(dv.appsSession(device.startsWith(Devices.LINUX) ? device : Devices.LINUX + Devices.TERMUX), false);
                }
                return;
            }
            if (runningSession != null) {
                startXFor(key);
                return;
            }
            // Nothing known to run. What the list last read may be old (the Launchpad reads nothing): a desktop started
            // from a shell would be taken for the apps-only X asked for here, xdesk only answers "already running"
            final Runnable asked = then;
            withFreshTermuxState(new Runnable() {
                @Override public void run() {
                    // Not if the user moved on, or X came by itself meanwhile (desktopConnected ran it)
                    if (desktopThen == asked && key.equals(pendingContent)) startXFor(key);
                }
            });
        }
        /**
         * A Linux program was opened and X is not on the screen (DEVICES.md §2). Something runs: back to it, the
         * program opens in whatever it is, apps only or the whole desktop of any environment. Nothing runs: the light
         * X of the program's environment ("NAME+apps": X, window manager, no desktop). Never the whole desktop.
         */
        private void startXFor(String key) {
            if (session.isActive() && session.isConnected()) {
                desktopConnected();
            } else if (runningSession != null && !session.isActive()) {
                // The same as tapping the running session: a waiting screen and the connect timeout, which fails us
                reattach();
            } else if (runningSession != null) {
                // The X window is placed and has not connected: without a timeout the open would wait for ever
                if (!ui.hasCallbacks(connectTimeout)) ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            } else {
                // Also with the X window already waiting for an X server: it sets the connect timeout, which fails us
                Devices dv = Devices.get(XDeskActivity.this);
                String device = dv.deviceOf(key);
                launchDesktop(dv.appsSession(device.startsWith(Devices.LINUX) ? device : Devices.LINUX + Devices.TERMUX), false);
            }
        }
        @Override public boolean wanted(String key) {
            return key.equals(pendingContent);
        }
        @Override public void open(String key) {
            // The normal way anything is opened or switched to, as the card's open does (a Mac app's save dialog
            // after a quit, MacSource.quitApp): the Launchpad still open goes away first
            if (SwitcherOverlay.isShown()) SwitcherOverlay.hide(switcherHost);
            chooseKey(key);
        }
        @Override public void freeDisplay() {
            releaseLatches();
            if (apps.isActive()) appWasInFront = true;
            else if (session.isActive()) appWasInFront = false;
            if (session.isActive()) leaveDesktop();
            apps.suspend();
        }
        @Override public void contentChanged() {
            chooser.refreshContents();
            refreshDocks();
        }
        @Override public void contentEnded(String key) {
            if (key.equals(pendingContent)) pendingContent = null;
            if (!key.equals(contentKey)) return;
            contentKey = null;
            // Giving the keys to the phone lasts for one content (chooseKey): with it gone they are the screen's
            // again, or the home that comes now had neither keyboard nor mouse
            phoneKeys = true;
            setTarget(apps.isActive() ? apps.input() : session.isActive() ? session.input() : null);
            if (!key.startsWith(LinuxSource.PREFIX)) resumeUnderContent();
            refreshDocks();
            updateStatus();
            // Nothing to capture the mouse for any more: it is the phone's again
            updateCapture();
        }
        @Override public void contentGone(String key) {
            XDeskActivity.this.contentGone(key);  // this anonymous class's own contentGone would call itself for ever
        }
    };

    /**
     * MACMENU.md §8, for every kind of content: the content on the external screen disappeared on its own — a Mac
     * app quit (from the card, ⌘Q, the menu later, or on the Mac: its windows leave the list), a Mac or Linux
     * window closed, an Android app finished or removed. XDesk shows the previous content (the Dock's second item)
     * when it still exists, else the Launchpad home. Never for the user's own moves — leaving the content for
     * another, stopping a device, disconnecting a Mac, ending XDesk, the display going away: those keep what they
     * do today (contentEnded above).
     *
     * @param key the gone content's key, or an "a:" key for the Android app that finished on the screen
     */
    private void contentGone(String key) {
        if (key == null) return;
        boolean appCase = ChooserView.isAppKey(key);
        if (appCase) {
            if (!apps.isActive()) return;
        } else {
            if (key.equals(pendingContent)) pendingContent = null;
            if (!key.equals(contentKey)) return;
            contentKey = null;
            // Giving the keys to the phone lasts for one content (chooseKey): with it gone they are the screen's
            // again, or what comes now had neither keyboard nor mouse
            phoneKeys = true;
            setTarget(apps.isActive() ? apps.input() : session.isActive() ? session.input() : null);
            // A Mac screen still up (its window closed, the app quit) stops its stream and finishes
            if (key.startsWith(MacSource.PREFIX)) {
                ContentSource s = Contents.sourceOf(key);
                if (s != null) s.hidden(key);
            }
        }
        refreshDocks();
        updateStatus();
        // The Launchpad home comes back by itself when nothing took the screen (updateCapture); with a previous
        // content, the normal way of opening it (chooseKey) takes the screen instead
        updateCapture();
        if (displayGoing) return;  // its display is gone: nothing to show instead (DISPLAY.md §5.4)
        String previous = previousKey(key);
        // A Linux whole desktop that is not running would be started for it (xdesk switch from the apps-only X): a
        // program closing is no reason to bring up a desktop (owner, 2026-11-19). One that runs is shown again
        if (previous != null && previous.startsWith(ChooserView.recentKey(""))
                && !ChooserView.sessionOf(previous).equals(runningSession)) previous = null;
        if (previous != null && Switchboard.get(this).exists(previous)) {
            chooseKey(previous);
            return;
        }
        if (appCase) {
            // Nothing took the screen: the empty app session is off it (as when a content takes over), the home comes
            apps.suspend();
            updateCapture();
        }
    }

    /** 桌面连上了：先做等着的事。 */
    private void desktopConnected() {
        // Waiting for the other X server: the one connected now is not the program's
        if (desktopThen != null && desktopWantBuiltin != null) {
            IBinder shown = XServers.shown();
            if (shown == null || XServers.isBuiltin(shown) != desktopWantBuiltin) return;
        }
        desktopWantBuiltin = null;
        Runnable then = desktopThen;
        desktopThen = null;
        desktopFailed = null;
        if (then != null) then.run();
    }

    /** 桌面没能连上：等着的内容报失败。 */
    private void desktopFailedToConnect() {
        desktopWantBuiltin = null;
        ContentSource.Result failed = desktopFailed;
        String key = desktopFor;
        desktopThen = null;
        desktopFailed = null;
        if (failed != null) failed.failed(key, getString(R.string.xd_content_no_desktop));
    }

    private void chooseKey(String key) {
        phoneKeys = true;  // whatever opens starts with the keys and mouse; giving them to the phone lasts for one content
        if (Contents.sourceOf(key) != null) {
            chooseContent(key);
            return;
        }
        leaveContentChoice();
        if (!ChooserView.isAppKey(key)) {
            chooserHost.onSessionChosen(ChooserView.sessionOf(key));
            return;
        }
        AppCatalog.App a = catalog.find(ChooserView.componentOf(key));
        if (a != null) chooseApp(a);
    }

    /** 已经有安卓应用在外接屏上就直接切换，否则走完整的启动流程。 */
    private void chooseApp(AppCatalog.App a) {
        // The app on the screen, for contentGone to skip when it finishes (MACMENU.md §8)
        appKey = ChooserView.recentKey(a);
        if (apps.isActive() && apps.isStarted() && apps.hasDisplay() && apps.hasInjector()) {
            rememberRecent(ChooserView.recentKey(a));
            apps.switchTo(a);
            showOpened();
        } else {
            retryRequested = true;
            startApp(a);
        }
    }

    /** 问 Termux 现在跑着什么，答了或等不到时都接着走。 */
    private void withFreshTermuxState(final Runnable then) {
        if (isFinishing() || isDestroyed()) return;
        if (!installed(PKG_TERMUX) || checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        final boolean[] done = new boolean[1];
        final Runnable once = new Runnable() {
            @Override public void run() {
                if (done[0] || isFinishing() || isDestroyed()) return;
                done[0] = true;
                then.run();
            }
        };
        boolean sent = TermuxCommand.run(this, TERMUX_STATE_COMMAND, new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) {
                if (isFinishing() || isDestroyed()) return;
                parseTermuxState(stdout);
                once.run();
            }
            @Override public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                termuxFailed(message);
                once.run();
            }
        });
        if (sent) ui.postDelayed(once, TERMUX_SWITCH_WAIT_MS);
        else once.run();
    }

    private void refreshChooser() {
        chooser.setStopping(session.stopping());
        updateActivationPrompt();
        updateChooserDisplay();
        refreshTermux();
        // 先用上次存的，马上就有；后台读完再更新
        refreshDocks();
        catalog.refresh(new Runnable() {
            @Override public void run() { refreshDocks(); }
        });
    }

    private void updateChooserDisplay() {
        Display d = externalDisplay();
        if (d != null && displayNoticeShown) {
            displayNoticeShown = false;
            setLauncherNotice(null);
        }
        chooser.setDisplayConnected(d != null, TargetDisplay.isCar(d));
        updateChooserTop();
        audioRoute.setCar(carConnected());
    }

    /** 要外接屏才能做的事：在启动器上一直说着，接上屏为止。 */
    private void needDisplayNotice() {
        if (!launcherShown()) {
            if (apps.isActive() || session.isActive()) openChooserOverSession();
            else showChooser();
        }
        // The console's banner and the Launchpad's line open the receiver picker (NoDisplay.dc.html)
        setLauncherNotice(getString(NO_DISPLAY), this::showWirelessPicker);
        displayNoticeShown = true;
    }

    /** Android Auto is connected now: CarActivity sits on the car's display. Unlike onCar(), this turns false the
     *  moment the car goes away, whatever content still lives on its display. */
    private boolean carConnected() {
        return TargetDisplay.car(displays) != null;
    }

    /** 现在用的是车机，不是外接屏：说法和能做的事不同。车机断开时车机上的应用还在它的显示器上，还算车机。
     *  Sound routing and the chip do not use this: they follow carConnected(). */
    private boolean onCar() {
        return apps.isActive() ? apps.isOnCar() : TargetDisplay.isCar(externalDisplay());
    }

    private List<String> savedLines(String pref) {
        String saved = prefs.getString(pref, "");
        if (saved.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(Arrays.asList(saved.split("\n")));
    }

    /**
     * Termux removed or installed anew: everything XDesk kept of it goes (DEVICES.md §1). A new Termux need not have
     * the old programs or desktops, so favourites and recents of Linux go too, with the sessions and the program list.
     * When it was a device the user is told, it was taken away from under them.
     */
    private void forgetGoneTermux() {
        Devices dv = Devices.get(this);
        int what = dv.forgetGoneTermux();
        if (what == Devices.TERMUX_SAME) return;
        if (what != Devices.TERMUX_FORGOTTEN) {
            boolean removed = what == Devices.TERMUX_REMOVED;
            new SettingsUi(this).confirm(getString(removed ? R.string.xd_termux_gone_title : R.string.xd_termux_new_title),
                getString(removed ? R.string.xd_termux_gone : R.string.xd_termux_new), getString(R.string.xd_ok), null);
        }
        SharedPreferences.Editor ed = prefs.edit().remove(PREF_SESSIONS);
        for (String pref : new String[]{PREF_DOCK, PREF_RECENTS}) {
            List<String> keep = new ArrayList<>();
            for (String key : savedLines(pref)) if (!dv.deviceOf(key).startsWith(Devices.LINUX)) keep.add(key);
            ed.putString(pref, String.join("\n", keep));
        }
        ed.apply();
        LinuxSource.forgetCatalog(this);
        Log.i(TAG, "Termux is gone or new: its sessions, programs, favourites and recents are forgotten");
        refreshDocks();
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

    private void setChooserTermux(List<String> sessions, String defaultName, String running, boolean x, String hint,
            String versionHint) {
        runningSession = running;
        termuxVersionHint = versionHint;
        chooser.setTermux(sessions, defaultName, running, x, hint, versionHint);
    }

    /** 列出环境和正在运行的东西。列不出时用上次的结果，并说明原因。 */
    private void refreshTermux() {
        ui.removeCallbacks(termuxNoReply);
        if (isFinishing() || isDestroyed()) return;
        forgetGoneTermux();
        if (!installed(PKG_TERMUX)) {
            setChooserTermux(new ArrayList<String>(), null, null, false, getString(R.string.xd_termux_not_installed), null);
            return;
        }
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            setChooserTermux(savedSessions(), null, null, false, getString(R.string.xd_termux_permission), null);
            return;
        }
        // The last read's version notices stay until the new one replaces them
        setChooserTermux(savedSessions(), null, runningSession, false, null, termuxVersionHint);
        boolean sent = TermuxCommand.run(this, TERMUX_STATE_COMMAND, new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) {
                if (!isFinishing() && !isDestroyed()) parseTermuxState(stdout);
            }
            @Override public void onFailure(String message) {
                if (!isFinishing() && !isDestroyed()) termuxFailed(message);
            }
        });
        if (sent) ui.postDelayed(termuxNoReply, TERMUX_REPLY_TIMEOUT_MS);
        else termuxNoReply.run();
    }

    /** Termux 回了话但没列出来：不用再等超时，直接说为什么。环境用上次的。 */
    private void termuxFailed(String message) {
        ui.removeCallbacks(termuxNoReply);
        setChooserTermux(savedSessions(), null, runningSession, false, message, termuxVersionHint);
    }

    /** What one read of xdesk told, the same fields for both outputs: the old three commands and the JSON of CLI.md §2. */
    private static final class TermuxState {
        final List<String> sessions = new ArrayList<String>();
        /** xdesk envs lines: name, kind, apps or -, the sessions joined by "/", tab separated (Devices.setEnvironments). */
        final List<String> envLines = new ArrayList<String>();
        String defaultName, running, xLayout;
        boolean x, xIme;
        /** The command's own version (CLI.md §1); null when it is too old to report one. */
        String cliVersion;
    }

    /** 解析 xdesk app state 的 JSON（CLI.md §2），旧脚本退回三段文字的输出。 */
    private void parseTermuxState(String stdout) {
        ui.removeCallbacks(termuxNoReply);
        TermuxState state;
        if (stdout.trim().startsWith("{")) {
            try {
                state = parseTermuxStateJson(stdout.trim());
            } catch (JSONException e) {
                Log.w(TAG, "xdesk app state is not JSON; reading it as the old output", e);
                state = parseTermuxStateText(stdout);
            }
        } else {
            state = parseTermuxStateText(stdout);
        }
        applyTermuxState(state);
    }

    /** 旧输出：xdesk list、status 和 envs。New scripts add an "xdesk: VERSION" line to status (CLI.md §1). */
    private static TermuxState parseTermuxStateText(String stdout) {
        TermuxState s = new TermuxState();
        boolean status = false;
        // After a second "---": xdesk envs, a tab separated line per environment
        boolean envs = false;
        for (String line : stdout.split("\n")) {
            if (line.equals("---")) {
                envs = status;
                status = true;
            } else if (envs) {
                if (line.indexOf('\t') > 0) s.envLines.add(line);
            } else if (!status) {
                if (line.startsWith("* ") || line.startsWith("  ")) {
                    String name = line.substring(2).trim();
                    if (name.isEmpty()) continue;
                    s.sessions.add(name);
                    if (line.startsWith("* ")) s.defaultName = name;
                }
            } else if (line.startsWith("X server: running")) {
                s.x = true;
            } else if (line.startsWith("Session: ") && !line.equals("Session: none")) {
                s.running = line.substring("Session: ".length()).trim();
            } else if (line.startsWith("Input method: ")) {
                // An input method on the X side (fcitx5): an xdesk that knows none prints no such line
                s.xIme = !line.startsWith("Input method: none");
            } else if (line.startsWith("Keyboard layout: ")) {
                // The remembered X side layout (INPUT.md §5.2): "from X" means none is set. An xdesk that knows
                // none prints no such line
                s.xLayout = line.substring("Keyboard layout: ".length()).trim();
                if (s.xLayout.equals("from X")) s.xLayout = null;
            } else if (line.startsWith("xdesk: ")) {
                // A script new enough to say its version, but too old for app state (CLI.md §1)
                s.cliVersion = line.substring("xdesk: ".length()).trim();
                if (s.cliVersion.isEmpty()) s.cliVersion = null;
            }
        }
        return s;
    }

    /** xdesk app state: one line of JSON with everything the three commands printed (CLI.md §2). */
    private static TermuxState parseTermuxStateJson(String stdout) throws JSONException {
        JSONObject o = new JSONObject(stdout);
        int format = o.getInt("format");
        // A newer format only adds to this one (CLI.md §2): read what is here
        if (format > 1) Log.w(TAG, "xdesk app state format " + format + " is newer than this app; reading what it knows");
        TermuxState s = new TermuxState();
        s.cliVersion = string(o, "version");
        JSONArray sessions = o.optJSONArray("sessions");
        if (sessions != null) for (int i = 0; i < sessions.length(); i++) {
            String name = sessions.getString(i).trim();
            if (!name.isEmpty()) s.sessions.add(name);
        }
        s.defaultName = string(o, "default");
        s.running = string(o, "running");
        JSONObject x = o.optJSONObject("x");
        s.x = x != null && x.optBoolean("running", false);
        // x.started is not read: an X server from before an update is found by asking it its build once connected
        // (checkServerBuild), which also offers the restart
        s.xIme = o.optBoolean("ime", false);
        s.xLayout = string(o, "layout");
        JSONArray envs = o.optJSONArray("envs");
        if (envs != null) for (int i = 0; i < envs.length(); i++) {
            JSONObject e = envs.getJSONObject(i);
            String name = string(e, "name");
            if (name == null || name.isEmpty()) continue;
            String kind = string(e, "kind");
            JSONArray names = e.optJSONArray("sessions");
            ArrayList<String> sess = new ArrayList<String>();
            if (names != null) for (int j = 0; j < names.length(); j++) {
                String n = names.getString(j);
                if (!n.isEmpty()) sess.add(n);
            }
            // The line xdesk envs prints: name, kind, apps or -, the sessions joined by "/"
            s.envLines.add(name + "\t" + (kind != null ? kind : "") + "\t" + (e.optBoolean("apps", false) ? "apps" : "-")
                + "\t" + String.join("/", sess));
        }
        return s;
    }

    /** A JSON string that may be null; a missing one counts as null. */
    private static String string(JSONObject o, String key) throws JSONException {
        return o.isNull(key) ? null : o.getString(key);
    }

    /** 两种输出最后都到这里：xdesk 说的东西放到 App 别处读的地方。 */
    private void applyTermuxState(TermuxState s) {
        // Its listener sets the key mode (keyModeFollows)
        Devices.get(this).setXIme(s.x && s.xIme);
        Devices.get(this).setXLayout(s.xLayout);
        prefs.edit().putString(PREF_SESSIONS, String.join("\n", s.sessions)).apply();
        // Just after XDesk started or switched a session xdesk may still report none, or the one being replaced
        String running = s.running;
        if (android.os.SystemClock.uptimeMillis() < launchGuardUntil && runningSession != null
                && (running == null || running.equals(launchedFrom))) running = runningSession;
        String before = runningSession;
        Devices.get(this).setEnvironments(s.envLines);
        cliVersion = s.cliVersion;
        String versionHint = versionHint(s);
        // No desktop is fine when an environment's programs open one by one (Termux always can, CLI.md §3.1): only
        // with neither is there nothing to show
        setChooserTermux(s.sessions, s.defaultName, running, s.x,
            s.sessions.isEmpty() && !anyEnvWithApps(s.envLines) ? getString(R.string.xd_termux_no_sessions) : null,
            versionHint);
        // What the screen shows is what runs: a window recreated does not know the name; "NAME+apps" of a session
        // without an apps-only variant is started whole (xdesk start_session)
        boolean sameSession = running != null && desktopName != null && !running.equals(desktopName)
            && stripAppsOnly(running).equals(stripAppsOnly(desktopName));
        if (session.isActive() && running != null && (desktopName == null || sameSession)) desktopName = running;
        else if (java.util.Objects.equals(before, running)) return;
        refreshDocks();
        updateStatus();
        updateCapture();
    }

    /**
     * CLI.md §1: the command must be this app's version, and a script too old to say one is not. A non-release app
     * ("dev-…") is never compared, and neither is a script copied from the repository ("dev").
     */
    private String versionHint(TermuxState s) {
        String app = BuildConfig.VERSION_NAME;
        if (app == null || app.isEmpty() || app.contains("dev") || !Character.isDigit(app.charAt(0))) return null;
        String cli = s.cliVersion;
        if (cli == null) return getString(R.string.xd_termux_cli_mismatch, getString(R.string.xd_termux_cli_old), app);
        // Only an older command: updating a newer one to this version would take it back
        if (cli.equals("dev") || compareVersions(withoutV(cli), withoutV(app)) >= 0) return null;
        return getString(R.string.xd_termux_cli_mismatch, cli, app);
    }

    /**
     * Release versions such as 0.6.0, 0.6.0-beta.4: the numbers first, then a release before its betas are newer
     * than none of them ("0.6.0" &gt; "0.6.0-beta.9"), betas by their number. Unknown parts compare as text.
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("-", 2), pb = b.split("-", 2);
        int c = compareDotted(pa[0], pb[0]);
        if (c != 0) return c;
        if (pa.length == 1 || pb.length == 1) return pb.length - pa.length;
        return compareDotted(pa[1], pb[1]);
    }

    private static int compareDotted(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "", q = i < y.length ? y[i] : "";
            int c;
            if (p.matches("\\d+") && q.matches("\\d+")) c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            else c = p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    /** An xdesk envs line (name, kind, "apps" or "-", sessions) of an environment whose programs can be opened. */
    private static boolean anyEnvWithApps(List<String> envLines) {
        for (String line : envLines) {
            String[] f = line.split("\t", -1);
            if (f.length >= 3 && "apps".equals(f[2])) return true;
        }
        return false;
    }

    private static String withoutV(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }

    /** The version mismatch's action: xdesk update, or for a script too old for it, its install line with this version. */
    private void updateXdeskCommand() {
        final String appVersion = withoutV(BuildConfig.VERSION_NAME);
        if (cliVersion == null) {
            TermuxCommand.copyInstallAndOpen(this, "v" + appVersion);
            return;
        }
        // It downloads, and installs missing Termux packages first: it can take a while
        Toast.makeText(this, R.string.xd_termux_cli_updating, Toast.LENGTH_SHORT).show();
        boolean sent = TermuxCommand.run(this, SCRIPT + " update " + appVersion, new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) {
                toastLastLine(stdout);
                refreshTermux();
            }
            @Override public void onFailure(String message) {
                Toast.makeText(XDeskActivity.this, message, Toast.LENGTH_LONG).show();
                refreshTermux();
            }
        });
        if (!sent) Toast.makeText(this, R.string.xd_termux_not_installed, Toast.LENGTH_LONG).show();
    }

    /** The install script ends with what it did ("xdesk updated to v…"); package installs come before it. */
    private void toastLastLine(String stdout) {
        String line = stdout.trim();
        int start = line.lastIndexOf('\n');
        if (start >= 0) line = line.substring(start + 1);
        if (!line.isEmpty()) Toast.makeText(this, line, Toast.LENGTH_LONG).show();
    }

    private static String stripAppsOnly(String session) {
        return session.endsWith(Devices.APPS_ONLY) ? session.substring(0, session.length() - Devices.APPS_ONLY.length()) : session;
    }

    /** The session XDesk has just asked xdesk to start or switch to, until X is there; and what ran before. */
    private String launchedSession, launchedFrom;
    private long launchGuardUntil;
    private static final long LAUNCH_GUARD_MS = 25000;

    /**
     * X is connected after XDesk started or switched a session: that session is the one running, before xdesk status
     * is read again (only the phone's list reads it). The device tabs and the external home go by it.
     */
    private void adoptLaunched() {
        if (launchedSession == null) return;
        runningSession = launchedSession;
        launchedSession = null;
        Devices.get(this).setLinuxRunning(runningSession);
    }

    /** xdesk list and status, read without first showing the saved state as refreshTermux does. */
    private final Runnable syncTermux = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed() || !installed(PKG_TERMUX) || checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) return;
            TermuxCommand.run(XDeskActivity.this, TERMUX_STATE_COMMAND, new TermuxCommand.Callback() {
                @Override public void onOutput(String stdout) { if (!isDestroyed()) parseTermuxState(stdout); }
                @Override public void onFailure(String message) { }
            });
        }
    };

    /** Once the session has had time to start, and once more for a slow one. */
    private void syncTermuxLater() {
        ui.removeCallbacks(syncTermux);
        ui.postDelayed(syncTermux, 6000);
        ui.postDelayed(syncTermux, LAUNCH_GUARD_MS + 2000);
    }

    /**
     * 离开设置和等待屏幕。外接屏不因此改变：有会话就把启动器盖在它上面。只取消还没成功的启动。
     * 结束会话只有 endXDesk 和从最近任务划掉。
     */
    private void backToChooser() {
        activationFlow = false;
        awaitingRunCommand = AWAIT_NONE;
        pendingApp = null;
        shizukuScreens = false;
        closeActivation();
        closeShizukuPage();
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
        // A Linux program was the content, or one waited for this desktop: gone with it (abandon tells no listener)
        if (contentKey != null && contentKey.startsWith(LinuxSource.PREFIX)) contentsHost.contentEnded(contentKey);
        if (pendingContent != null && pendingContent.startsWith(LinuxSource.PREFIX)) pendingContent = null;
        desktopThen = null;
        desktopFailed = null;
        phoneKeys = true;  // given to the phone for this desktop only (chooseKey)
        takeDesktopWhenBack = false;
        ui.removeCallbacks(retakeDesktop);
        setDesktopTaken(false);
        apps.desktopEnded();
        releaseLatches();
        setTarget(null);
        router.cancelAll();
        session.abandon();
        MainActivity m = MainActivity.getInstance("");
        if (m != null) m.finishAndRemoveTask();
        // 应用留在了桌面所在的那种屏上，车机上是它自己的显示器
        Display d = desktopDisplay();
        if (d != null && apps.resumeApp(d, prefs.getFloat(PREF_MOUSE_SPEED, 1f), prefs.getBoolean(PREF_ALWAYS_CURSOR, false))) {
            appKey = ChooserView.recentKey(apps.app());  // the app on the screen again, for contentGone
            showSetup(getString(R.string.xd_returning_to, apps.label()), getString(R.string.xd_returning_to_message), true, null, null);
            return;
        }
        if (apps.hasInjector()) apps.stop();
        showChooser();
        dashboardTried = Display.INVALID_DISPLAY;
        ensureDashboard();
    }

    /** 明确结束：应用移回手机，外接屏恢复镜像，键鼠、声音路由和界面大小恢复。Termux 桌面继续运行。 */
    private void confirmEndXDesk() {
        ui().confirm(getString(R.string.xd_end_xdesk_title), getString(R.string.xd_end_xdesk_message), getString(R.string.xd_end),
            SettingsUi.LEVEL_DANGER, new Runnable() {
            @Override public void run() {
                releaseLatches();
                client = null;
                router.cancelAll();
                DesktopService.endAll(XDeskActivity.this);
                finishAndRemoveTask();
            }
        });
    }

    // --- 外接屏仪表盘 ---

    /** XDesk 开着又有外接屏时，它就在最底下。外接屏上已有应用或桌面时不放，否则它会盖住它们。车机上 CarActivity 就是仪表盘。 */
    private void ensureDashboard() {
        Display d = externalDisplay();
        if (d == null || TargetDisplay.isCar(d) || SplitStageActivity.isActive() || apps.hasAppOnDisplay() || session.isActive()) return;
        if (dashboardTried == d.getDisplayId()) return;
        startDashboard(d);
    }

    private void goHomeOnDisplay() {
        Display d = externalDisplay();
        if (d == null) return;
        try {
            Intent i = new Intent(this, DashboardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(d.getDisplayId());
            startActivity(i, o.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot show the dashboard", e);
        }
    }

    /** Recent apps and the dock, as a computer's list: key, tab, label. */
    private String appLines() {
        StringBuilder out = new StringBuilder();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(savedLines(PREF_RECENTS));
        keys.addAll(savedLines(PREF_DOCK));
        for (String key : keys) {
            if (!ChooserView.isAppKey(key)) continue;
            AppCatalog.App a = catalog.find(ChooserView.componentOf(key));
            if (a != null) out.append(key).append('\t').append(a.label).append('\n');
        }
        return out.toString();
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
            showSetup(getString(R.string.xd_starting_app, app.label), getString(R.string.xd_waiting_previous_injector), true, null, null);
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
            showPermitSetup(getString(R.string.xd_permit_overlay), getString(R.string.xd_overlay_message),
                getString(R.string.xd_permit_overlay_path), null, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openOverlaySettings();
                }
            });
        } else {
            closeActivation();
            closeShizukuPage();
            pendingApp = null;
            shizukuScreens = false;
            rememberRecent(ChooserView.recentKey(app));
            showSetup(getString(R.string.xd_starting_app, app.label), getString(R.string.xd_connecting_injector), true, null, null);
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
        else if (!shell.isPaired()) text = getString(R.string.xd_prompt_activate);
        else if (shell.isStarting()) text = getString(R.string.xd_prompt_starting);
        else text = getString(R.string.xd_prompt_start);
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
            showSetup(app != null ? getString(R.string.xd_starting_app, app.label) : getString(R.string.xd_starting_injector_title), getString(R.string.xd_starting_injector), true, null, null);
            return;
        }
        showSetup(getString(R.string.xd_one_more_step), shell.problem() != null ? shell.problem() : getString(R.string.xd_injector_not_running), false, getString(R.string.xd_act_open_wireless), new View.OnClickListener() {
            @Override public void onClick(View v) { openWirelessSettings(); }
        });
        addSetupChoice(getString(R.string.xd_pair_again), new View.OnClickListener() {
            @Override public void onClick(View v) { shell.forgetPairing(); }
        });
    }

    private void openActivation() {
        showSetup(getString(R.string.xd_act_title), "", false, null, null);
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

    /** 通知权限只问一次：拒绝过就不再弹，没有通知也能用。@return 问了，结果在 onRequestPermissionsResult */
    private boolean askNotificationsOnce(boolean pairing) {
        // Before Android 13 there is no such permission: notifications just work, nothing to ask
        if (android.os.Build.VERSION.SDK_INT < 33) return false;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            || prefs.getBoolean(PREF_NOTIFICATIONS_ASKED, false)) return false;
        prefs.edit().putBoolean(PREF_NOTIFICATIONS_ASKED, true).apply();
        // Said first in PermitCard's look; "Not now" goes on without it (the page has its own field)
        ui().permitSheet(getString(R.string.xd_permit_post), getString(pairing ? R.string.xd_permit_post_why : R.string.xd_permit_post_desktop_why),
            pairing ? getString(R.string.xd_permit_post_path) : null, null,
            () -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS),
            () -> {
                if (pairingAfterPermission) {
                    pairingAfterPermission = false;
                    startPairingService();
                }
            });
        return true;
    }

    /** 配对框开着时才有配对码，所以先把找端口的服务开起来，用户在系统设置里也能从通知输入。 */
    private void beginPairing() {
        if (askNotificationsOnce(true)) {
            pairingAfterPermission = true;
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
            Toast.makeText(this, R.string.xd_no_developer_options, Toast.LENGTH_LONG).show();
        }
    }

    /** 选择界面上说一声还差什么，比如重启后无线调试关了。 */
    private void showShellProblem() {
        String problem = apps.shizukuState() == AppSession.SHIZUKU_READY ? null : apps.shell().problem();
        if (problem != null) setLauncherNotice(problem);
        else if (shownShellProblem != null) setLauncherNotice(null);
        shownShellProblem = problem;
        updateActivationPrompt();
    }

    /**
     * 用户选了 Shizuku，或者系统太旧用不了无线调试：Shizuku.dc.html 的整页。页面上三步都看得见，缺哪一步、哪一步有事做
     * 就写在哪一步上；“其他情况”只列当前这种。状态变了（装好、启动、授权）由 onInjectorSourceChanged 再画一次。
     */
    private void showShizukuSetup() {
        if (shizukuPage != null && shizukuPage.isShowing()) {
            shizukuPage.refresh();
            return;
        }
        showSetup(getString(R.string.xd_act_use_shizuku), "", false, null, null);
        setupBrand.setVisibility(View.GONE);
        shizukuPage = new ShizukuPage(this, ui(), apps, new ShizukuPage.Actions() {
            @Override public void download() {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HOME)));
            }
            @Override public void openShizuku() {
                Intent open = getPackageManager().getLaunchIntentForPackage(AppSession.SHIZUKU_PACKAGE);
                if (open != null) startActivity(open);
            }
            @Override public void requestPermission() {
                apps.requestPermission();
            }
            @Override public void useWireless() {
                shizukuScreens = false;
                closeShizukuPage();
                if (pendingApp != null) startApp(pendingApp);
                else showActivation(null);
            }
            @Override public void cancelled() {
                shizukuPage = null;
                backToChooser();
            }
        });
    }

    private void closeShizukuPage() {
        if (shizukuPage != null) shizukuPage.close();
        shizukuPage = null;
    }

    /** 把外接屏让给安卓应用。桌面窗口留在后面，桌面继续运行，切回来直接放到前面；这期间桌面没有声音。 */
    private void leaveDesktop() {
        if (!session.isActive()) return;
        // 这之后 onSessionChanged 会把接管清掉
        if (desktopTaken) takeDesktopWhenBack = true;
        // 锁定的修饰键是按在桌面上的，换到应用之前在桌面上松开
        releaseLatches();
        ui.removeCallbacks(connectTimeout);
        session.abandon();
        updateCapture();
    }

    private void attachApp() {
        setTarget(apps.input());
        showOpened();
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
        audioRoute.setCar(carConnected());
        if (desktopOnCar) Log.i(TAG, "desktop on the car, display=" + d.getDisplayId() + " size=" + desktopSize(d));
        LorieView.capFramerate(desktopOnCar ? d.getDisplayId() : Display.INVALID_DISPLAY, (int) AppSession.CAR_REFRESH_HZ);
        placeX11(d.getDisplayId());
        applySize(d);
        session.attach();
        apps.connectForAudio();
        setTarget(session.input());
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
        showSetup(getString(R.string.xd_reattach_title), getString(R.string.xd_reattach, stripAppsOnly(runningSession)), true, null, null);
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void showWaiting() {
        // Waiting.dc.html: the spinner, the title, "在 Termux 里运行", the command, and the two full-width buttons
        showSetup(getString(R.string.xd_waiting_title), getString(R.string.xd_waiting_run), false, getString(R.string.xd_waiting_action), new View.OnClickListener() {
            @Override public void onClick(View v) { launchDesktop(null, false); }
        });
        setupSpinTop.setVisibility(View.VISIBLE);
        setupExtras("xdesk start", getString(R.string.xd_waiting_any));
        setupMessage.setTextSize(13);
        stretchSetup();
    }

    /**
     * PermSheet mockup: opening a Linux desktop without the Termux command permission. Allow asks the system; the
     * quieter way copies "xdesk start" and opens Termux.
     */
    private void showRunCommandSheet(String name) {
        final SettingsUi s = ui();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(2));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.xd_ic_shield);
        icon.setColorFilter(Style.WARN);
        header.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView title = Style.label(this,
            getString(R.string.xd_perm_run_sheet_title, name != null ? name : getString(R.string.xd_perm_run_sheet_default)),
            Style.TEXT, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        TextView need = Style.label(this, Permissions.need(this, Permissions.TERMUX), Style.WARN, Style.TEXT_SMALL);
        need.setPadding(0, dp(3), 0, 0);
        texts.addView(need);
        header.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView note = Style.label(this, getString(R.string.xd_perm_run_sheet_note), Style.TEXT_DIM, 13);
        note.setPadding(dp(2), 0, dp(2), 0);
        note.setLineSpacing(dp(2), 1f);
        content.addView(note);
        final Dialog[] sheet = new Dialog[1];
        // PermSheet's one primary button is blue
        TextView allow = Style.button(this, getString(R.string.xd_perm_run_sheet_allow), Style.ACCENT, Style.TEXT_BODY, 12);
        allow.setOnClickListener(v -> {
            Style.tick(v);
            sheet[0].dismiss();
            requestPermissions(new String[]{PERM}, REQ_RUN_COMMAND);
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(44));
        ap.topMargin = dp(14);
        content.addView(allow, ap);

        LinearLayout or = new LinearLayout(this);
        or.setGravity(Gravity.CENTER_VERTICAL);
        or.setPadding(0, dp(14), 0, 0);
        View left = new View(this);
        left.setBackgroundColor(Style.LINE);
        or.addView(left, new LinearLayout.LayoutParams(0, dp(1), 1));
        TextView word = Style.label(this, getString(R.string.xd_perm_run_sheet_or), Style.TEXT_DIM, Style.TEXT_SMALL);
        word.setPadding(dp(10), 0, dp(10), 0);
        or.addView(word);
        View right = new View(this);
        right.setBackgroundColor(Style.LINE);
        or.addView(right, new LinearLayout.LayoutParams(0, dp(1), 1));
        content.addView(or, new LinearLayout.LayoutParams(-1, -2));

        TextView command = Style.label(this, "xdesk start", Style.TEXT, 13);
        command.setTypeface(Typeface.MONOSPACE);
        command.setGravity(Gravity.CENTER);
        command.setBackground(Style.rounded(this, Style.SURFACE_RAISED, 10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(38));
        cp.topMargin = dp(10);
        content.addView(command, cp);
        TextView open = Style.button(this, getString(R.string.xd_perm_run_sheet_termux), Style.SURFACE_RAISED,
            Style.TEXT_BODY, 12);
        open.setOnClickListener(v -> {
            Style.tick(v);
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("xdesk", "xdesk start"));
            Toast.makeText(this, R.string.xd_perm_copied, Toast.LENGTH_SHORT).show();
            Intent termux = getPackageManager().getLaunchIntentForPackage(PKG_TERMUX);
            if (termux != null) {
                try {
                    startActivity(termux);
                } catch (RuntimeException e) {
                    Log.w(TAG, "open Termux", e);
                }
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(44));
        op.topMargin = dp(10);
        content.addView(open, op);
        sheet[0] = s.bottomSheet(header, content);
    }

    /**
     * 替用户运行 xdesk start，只由用户的选择触发。只有这里需要 Termux 的权限。
     *
     * @param name 环境名，null 是默认环境
     * @param replace 结束运行中的另一个环境
     */
    private void launchDesktop(String name, boolean replace) {
        if (!installed(PKG_TERMUX)) {
            showSetup(getString(R.string.xd_no_termux_title), getString(R.string.xd_no_termux), false,
                getString(R.string.xd_no_termux_action), v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_DOWNLOAD))));
            return;
        }
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            pendingSession = name;
            pendingReplace = replace;
            // Opening a desktop from elsewhere: the bottom sheet, not a silent failure or a page away (PermSheet)
            showRunCommandSheet(name);
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
        askNotificationsOnce(false);
        // "NAME+apps" is the light X a Linux program is opened on: no desktop is starting
        boolean light = name != null && name.endsWith(Devices.APPS_ONLY);
        showSetup(getString(light ? R.string.xd_starting_x_title : R.string.xd_starting_desktop_title), getString(R.string.xd_starting_desktop), true, null, null);
        if (!session.launch(name, replace)) return;
        if (name != null) {
            Devices.get(this).setLinuxStarting(name);
            launchedSession = name;
            launchedFrom = runningSession;
            launchGuardUntil = android.os.SystemClock.uptimeMillis() + LAUNCH_GUARD_MS;
        }
        // xdesk switch 不断开 X，不会再有“连上了”的通知
        if (session.isConnected()) {
            adoptLaunched();
            syncTermuxLater();
            showOpened();
            updateStatus();
            return;
        }
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (r == REQ_PERM_CENTER) {
            // Asked from the permission centre, the detail page or the first-start checklist: only the pages change
            refreshPermissionPages();
            return;
        }
        // A grant that a permission page was waiting for too
        refreshPermissionPages();
        if (r == REQ_NOTIFICATIONS && pairingAfterPermission) {
            pairingAfterPermission = false;
            startPairingService();
            return;
        }
        if (r == REQ_LIST_SESSIONS) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED || shouldShowRequestPermissionRationale(PERM)) {
                showChooser();
                refreshTermux();
            } else {
                // Refused for good: the system asks no more, only the details page can allow it; back with it, the list
                awaitingRunCommand = AWAIT_LIST;
                showPermitSetup(getString(R.string.xd_permit_run_command), getString(R.string.xd_permit_run_command_why),
                    getString(R.string.xd_permit_run_command_denied_path), getString(R.string.xd_permit_details), v -> openAppDetails());
            }
            return;
        }
        if (r != REQ_RUN_COMMAND) return;
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            launchDesktop(pendingSession, pendingReplace);
            return;
        }
        awaitingRunCommand = AWAIT_DESKTOP;  // allowed on the details page: the desktop starts when XDesk is back
        showPermitSetup(getString(R.string.xd_permit_run_command), getString(R.string.xd_permit_run_command_why),
            getString(R.string.xd_permit_run_command_denied_path), getString(R.string.xd_permit_details), v -> openAppDetails());
    }

    /**
     * ConnectFail.dc.html's injector page, while an Android app is on the display and the injector is gone: the red
     * status line opens it. Separate from the Linux desktop's page (showConnectFailure): this one is about the
     * injector, and its Retry is the existing activation/reconnect flow.
     */
    private void showInjectorLoss() {
        showSetup(getString(R.string.xd_injector_lost_title), getString(R.string.xd_injector_lost_line), false,
            getString(R.string.xd_act_open_wireless), new View.OnClickListener() {
                @Override public void onClick(View v) { openWirelessSettings(); }
            });
        setupBrand.setVisibility(View.GONE);
        setupIconBox.setVisibility(View.VISIBLE);
        setupMessage.setTextColor(Style.TEXT_SOFT);
        setupMessage.setTextSize(14);
        setupNote2.setText(getString(R.string.xd_injector_lost_note));
        setupNote2.setVisibility(View.VISIBLE);
        addSetupChoice(getString(R.string.xd_retry), new View.OnClickListener() {
            @Override public void onClick(View v) { openInjectorFlow(); }
        });
        stretchSetup();
        // The quiet way back at the bottom, centred and without a fill, as the mockup draws it
        setupBack.setLayoutParams(stretchable(dp(44), dp(12), -2));
        setupBack.setTextColor(Style.ACCENT_2);
        setupBack.setBackground(Style.ripple(this, 0x00000000, 12));
    }

    private void showConnectFailure() {
        desktopFailedToConnect();
        // ConnectFail.dc.html's look (red round icon, quiet way back). The words stay the desktop's: this page is the
        // Linux desktop not answering, not the injector (the mockup's text is for the injector).
        showSetup(getString(R.string.xd_desktop_no_start_title), getString(R.string.xd_desktop_no_start), false,
            getString(R.string.xd_retry), new View.OnClickListener() {
                @Override public void onClick(View v) { launchDesktop(pendingSession, pendingReplace); }
            });
        setupBrand.setVisibility(View.GONE);
        setupIconBox.setVisibility(View.VISIBLE);
        setupMessage.setTextColor(Style.TEXT_SOFT);
        setupMessage.setTextSize(14);
        stretchSetup();
        // The quiet way out at the bottom, as the mockup draws it
        setupBack.setTextColor(Style.ACCENT_2);
        setupBack.setBackground(Style.ripple(this, 0x00000000, 12));
    }

    /** 界面重建时接回还在运行的会话。 */
    private void attachSession() {
        setTarget(session.input());
        if (session.isConnected()) {
            showOpened();
            updateStatus();
        } else {
            showWaiting();
        }
    }

    /** 分辨率的宽高比要和系统认定的外屏尺寸一致才能铺满（这台显示器系统认定 2560x1508）。 */
    private String desktopSize(Display d) {
        Point p = desktopSize(d, resolutionMode());
        return p.x + "x" + p.y;
    }

    /** The desktop's size on d in a resolution mode: by height, the width in the shape the window has. */
    private static Point desktopSize(Display d, int mode) {
        Point p = new Point();
        d.getRealSize(p);
        if (p.y > p.x) {
            Display.Mode m = d.getMode();
            p.set(m.getPhysicalWidth(), m.getPhysicalHeight());
        }
        // By the short side: a portrait monitor's 1080 is its width (audit, 17 November: it was halved by its height)
        int side = Math.min(p.x, p.y), s = side;
        if (mode == RES_AUTO && side > AUTO_MAX_HEIGHT) s = side / ((side + AUTO_MAX_HEIGHT - 1) / AUTO_MAX_HEIGHT);
        else if (mode == RES_1080) s = Math.min(1080, side);
        else if (mode == RES_720) s = Math.min(720, side);
        if (s == side) return p;
        return new Point(Math.round((float) s * p.x / side) & ~1, Math.round((float) s * p.y / side) & ~1);
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
        DesktopScale.apply(this, p, d, Integer.parseInt(size.substring(0, size.indexOf('x'))));
    }

    /** The screen's kind or size u changed (DISPLAY.md §4.1): the desktop and the wireless display follow, once. */
    private void screenSizingChanged() {
        Display d = desktopDisplay();
        if (session.isActive() && d != null) applySize(d);
        WirelessDisplay.get(this).sizingChanged();
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
            setStatus(Style.DANGER, getString(displayId == CarActivity.displayId() ? R.string.xd_place_failed_car : R.string.xd_place_failed_display, e.getMessage()));
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
        if (session.stopping() != null) return;
        String name = runningSession != null ? runningSession : desktopName;
        if (session.isActive()) desktopGone();
        session.stop(name);
        chooser.setStopping(session.stopping());
        // 切到安卓应用时桌面窗口留在了后面
        MainActivity m = MainActivity.getInstance("");
        if (m != null) m.finishAndRemoveTask();
        runningSession = null;
        desktopName = null;
        launchedSession = null;
        launchGuardUntil = 0;
        if (launcherShown()) refreshChooser();
    }

    /** The windows a Stop would close. */
    private int stopWindows() {
        LinuxSource linux = LinuxSource.get();
        return linux != null ? linux.windows().size() : 0;
    }

    /** The whole desktop a Stop would end, or null (the light X alone is not worth a question). */
    private String stopSession() {
        String running = runningSession != null ? runningSession : desktopName;
        return running != null && !running.endsWith(Devices.APPS_ONLY) ? running : null;
    }

    /**
     * Whether a Linux Stop asks on the phone first: something would be lost, or the permission to stop is missing.
     * The device bar's "Continue on the phone" (Switchboard.Action.onPhone) is this same answer.
     */
    private boolean stopAsks() {
        return checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED || stopWindows() > 0 || stopSession() != null;
    }

    // --- The built-in Linux beside Termux (XServers) ---

    /** Termux's X server (any X the built-in Linux did not start), the newest, or null. */
    private static IBinder termuxX() {
        IBinder found = null;
        for (IBinder b : XServers.all()) if (!XServers.isBuiltin(b)) found = b;
        return found;
    }

    /** The built-in Linux's whole desktop on the external screen: its X shown, started first if need be. */
    private void openBuiltinDesktop() {
        Display d = externalDisplay();
        if (d == null) {
            needDisplayNotice();
            return;
        }
        leaveContentChoice();
        pendingApp = null;
        rememberRecent(ChooserView.recentKey(Devices.BUILTIN_SESSION));
        desktopName = null;
        BuiltinLinux bl = BuiltinLinux.get(this);
        IBinder x = XServers.builtin();
        if (bl.state() == BuiltinLinux.State.RUNNING && x != null) {
            XServers.switchTo(x);
            // Running "apps only": its panel and desktop come in beside the open programs
            bl.showDesktop();
            if (session.isActive() && session.isConnected()) {
                showOpened();
                updateStatus();
            } else {
                if (!session.isActive()) attachDisplay(d);
                showBuiltinWaiting();
            }
            return;
        }
        if (bl.state() == BuiltinLinux.State.STARTING) bl.showDesktop();
        else bl.start();
        showBuiltinWaiting();
    }

    /** A program of the built-in Linux needs its X: shown if it runs, else the built-in Linux starts (builtinStarted). */
    private void needBuiltinX() {
        BuiltinLinux bl = BuiltinLinux.get(this);
        IBinder x = XServers.builtin();
        if (bl.state() == BuiltinLinux.State.RUNNING && x != null) {
            XServers.switchTo(x);
            Display d = externalDisplay();
            if (!session.isActive() && d != null) attachDisplay(d);
            return;
        }
        // Not running: only what its programs need (X and the window manager), as Termux's "+apps"
        if (bl.state() != BuiltinLinux.State.STARTING && bl.state() != BuiltinLinux.State.STOPPING) bl.startApps();
        else if (bl.state() == BuiltinLinux.State.STOPPING) desktopFailedToConnect();
    }

    /**
     * BuiltinLinux.start is done: its X is shown. With the desktop window already up (Termux's X on it) the new X
     * server is switched to as it announces itself (xServers.started); without, the window is placed and takes it.
     */
    private void builtinStarted() {
        Display d = externalDisplay();
        if (d == null) return;
        IBinder x = XServers.builtin();
        if (x != null) XServers.switchTo(x);
        if (!session.isActive()) {
            attachDisplay(d);
            if (desktopThen == null) showBuiltinWaiting();
        }
    }

    private void showBuiltinWaiting() {
        showSetup(getString(R.string.xd_bl_title), getString(R.string.xd_bl_starting_desktop), true, null, null);
    }

    private final BuiltinLinux.Listener builtinChanged = () -> {
        // Its start failed (or it was stopped) while a program of it waited for its X
        BuiltinLinux bl = BuiltinLinux.get(XDeskActivity.this);
        BuiltinLinux.State st = bl.state();
        if (desktopThen != null && Boolean.TRUE.equals(desktopWantBuiltin)
                && (st == BuiltinLinux.State.STOPPED || st == BuiltinLinux.State.NOT_INSTALLED)) {
            desktopFailedToConnect();
            if (launcherShown()) return;
            backToChooser();
        }
    };

    private final XServers.Listener xServers = new XServers.Listener() {
        @Override public void started(IBinder server, int uid) {
            // An X server that was just started is what the user just asked for (a desktop or a program of it, or a
            // desktop started in Termux): it is shown, the other keeps running
            XServers.switchTo(server);
        }
        @Override public void shownChanged() {
            // Its sound now, the device list's states, and whatever waited for this X
            AudioPlayer audio = session.audio();
            if (audio != null) audio.reconnect();
            Devices.get(XDeskActivity.this).touch();
            if (!session.isActive() || !session.isConnected() || XServers.shown() == null) return;
            if (desktopThen != null) {
                desktopConnected();
            } else if (pendingContent == null && (contentKey == null || !contentKey.startsWith(LinuxSource.PREFIX))) {
                // A whole desktop was chosen (or started from a shell): it is what the screen shows now
                showOpened();
            }
            updateStatus();
        }
    };

    private void confirmStop() {
        // A Linux device's Stop (DEVICES.md §2): the X server all Linux environments share ends, with everything on it.
        // Stops ask too, with the orange button: what happens is said before it does
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            ui().permitSheet(getString(R.string.xd_permit_run_command), getString(R.string.xd_end_desktop_no_permission),
                getString(R.string.xd_permit_run_command_denied_path), getString(R.string.xd_permit_details), () -> {
                    awaitingRunCommand = AWAIT_STOP;
                    openAppDetails();
                }, null);
            return;
        }
        int open = stopWindows();
        boolean desktop = stopSession() != null;
        String message = getString(desktop ? R.string.xd_end_desktop_message : R.string.xd_stop_x_message);
        if (open > 0) message += "\n\n" + getResources().getQuantityString(R.plurals.xd_stop_x_windows, open, open);
        ui().confirm(getString(desktop ? R.string.xd_end_desktop_confirm_title : R.string.xd_stop_x_title), message,
            getString(R.string.xd_stop), SettingsUi.LEVEL_STOP, new Runnable() {
                @Override public void run() { stopDesktop(); }
            });
    }
}
