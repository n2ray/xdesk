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
import android.os.Build;
import android.text.InputType;
import android.os.Bundle;
import android.os.SystemClock;
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
    private static final String TERMUX_STATE_COMMAND = DesktopSession.nonceEnv() + SCRIPT + " list; echo ---; " + SCRIPT + " status";
    private static final String SHIZUKU_HOME = "https://shizuku.rikka.app/";
    private static final int NO_DISPLAY = R.string.xd_no_display;
    private static final String TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/";

    private static final String PREF_SCROLL_SPEED = "pad_scroll_speed", PREF_COVER = "cover_external";
    private static final String PREF_OUTPUT = "audio_output", PREF_WIDTH = "desktop_width", PREF_SPEED = "pad_speed",
        PREF_DIM = "dim_idle", PREF_DIAG = "show_diag", PREF_ORIENTATION = "orientation", PREF_RECENTS = "recents",
        PREF_SESSIONS = "sessions", PREF_MOUSE_SPEED = "mouse_speed", PREF_DOCK = "dock", PREF_EXTERNAL_DOCK = "external_dock", PREF_ALWAYS_CURSOR = "always_cursor",
        PREF_FULL_PAD = "pad_fullscreen", PREF_NOTIFICATIONS_ASKED = "notifications_asked", PREF_DESKTOP_DENSITY = "desktop_density";
    /** Interface size of the external display under a Termux desktop: small enough for the input method's candidates. */
    private static final int DESKTOP_DENSITY = 168;
    /** IME this tall (part of the window) is a soft keyboard; less is the candidate bar of a physical keyboard. */
    private static final float SOFT_KEYBOARD_PART = 0.25f;
    private static final int MAX_RECENTS = 6;
    private static final long TERMUX_REPLY_TIMEOUT_MS = 8000;
    /** 切换时等 Termux 说哪个环境在运行，等不到就按上次知道的。 */
    private static final long TERMUX_SWITCH_WAIT_MS = 3000;
    /** xdesk stop 要等桌面退出，过一会再看状态。 */
    private static final int[] WIDTH_LABELS = {R.string.xd_width_native, R.string.xd_width_1920, R.string.xd_width_1280};
    private static final int[] WIDTHS = {0, 1920, 1280};  // 0 = 原生
    private static final int DEFAULT_WIDTH = 1920;
    private static final int[] ORIENTATION_LABELS = {R.string.xd_orientation_system, R.string.xd_orientation_portrait, R.string.xd_orientation_landscape};
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
    private static final long DIM_AFTER_MS = 60000, DIM_WITH_COMPUTER_MS = 5000;
    private static final float DIM_BRIGHTNESS = 0.01f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private InputManager inputs;
    private InputMethodManager ime;
    private SharedPreferences prefs;

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
    /** 启动器上的提示现在是 NO_DISPLAY。 */
    private boolean displayNoticeShown;
    private AppSession apps;
    private AppCatalog catalog;
    private AudioRoute audioRoute;
    private WirelessDisplay wireless;
    private WebServer web;
    /** The code a browser should type, shown on the phone while it pairs. */
    private android.app.Dialog webCodeDialog;
    private DockBar runningDock;
    private ExternalDock externalDock;
    private final ExternalLauncher.Host launcherHost = new ExternalLauncher.Host() {
        @Override public List<String> favorites() { return savedLines(PREF_DOCK); }
        @Override public String current() { return currentKey(); }
        @Override public String previous() { return previousKey(); }
        @Override public void open(String key) {
            if (ExternalLauncher.SWITCHER.equals(key)) showSwitcher(false);
            else chooseKey(key);
        }
    };
    private Dialog outputDialog;
    /** 设置页开着时的声音输出那一行。 */
    private TextView settingsOutputValue;
    /** 这次接上的桌面环境名，只当显示器时不知道。 */
    private String desktopName;
    private int lastOrientation;
    private TextView setupTitle, setupMessage, setupAction, setupSecond, setupBack;
    private ProgressBar setupProgress;
    private TextView statusText, diagText;
    /** Holds the status dot and text: shown only for warnings and errors, the buttons already say the rest. */
    private View statusRow;
    private Style.IconButton audioBtn, touchBtn, forwardBtn, fullscreenBtn, launcherBtn, settingsBtn, backBtn;
    private View statusDot;
    private TouchpadView touchpad;
    private KeyInputView keyView;

    private volatile InputSink client;
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
            setChooserTermux(savedSessions(), null, runningSession, false, getString(R.string.xd_termux_no_reply));
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
        @Override public void move(int dx, int dy) {
            wireless.phonePointerMoved();
            if (client != null) client.move(dx, dy);
        }
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
            setStatus(Style.WARN, getString(R.string.xd_display_gone_desktop));
            needsPlacement = true;
            // 系统会把桌面窗口挪到手机屏上盖住触摸板，把 App 拉回前面
            refocusSelf();
        }
        @Override public void onDisplayChanged(int id) {
            if (apps.isActive()) apps.displayChanged(id);
            // The X desktop follows its display too (a receiver window resized, a tablet turned), once the size has
            // settled: DISPLAY.md §4.3. Same size again changes nothing (the preference stays equal).
            Display shown = desktopDisplay();
            if (session.isActive() && shown != null && shown.getDisplayId() == id) {
                ui.removeCallbacks(desktopFollowsDisplay);
                ui.postDelayed(desktopFollowsDisplay, RESIZE_SETTLE_MS);
            }
        }
    };

    /** DISPLAY.md §4.3: a size is committed once it has not changed for this long. */
    private static final long RESIZE_SETTLE_MS = 300;
    private final Runnable desktopFollowsDisplay = () -> {
        Display d = desktopDisplay();
        if (session.isActive() && d != null) applySize(d);
    };

    private final DesktopSession.Listener sessionListener = new DesktopSession.Listener() {
        @Override public void onSessionChanged(boolean c) {
            // 桌面窗口没了，焦点回不去它那里
            if (!c) setDesktopTaken(false);
            if (c) {
                ui.removeCallbacks(connectTimeout);
                showRunning();
                desktopConnected();
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
            // 不自动重启：启动和结束桌面都由用户决定
            runningSession = null;
            desktopName = null;
            desktopGone();
            String stopped = getString(byUser ? R.string.xd_desktop_ended : R.string.xd_desktop_crashed);
            Toast.makeText(XDeskActivity.this, stopped, Toast.LENGTH_LONG).show();
            chooser.setNotice(getString(byUser ? R.string.xd_desktop_ended_notice : R.string.xd_desktop_crashed_notice));
        }
        @Override public void onDesktopStopFinished() {
            chooser.setStopping(null);
            if (chooser.getVisibility() == View.VISIBLE) refreshTermux();
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
            showSetup(getString(R.string.xd_cannot_run_on_display), message, false, getString(R.string.xd_back_to_launcher), new View.OnClickListener() {
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

    private final WirelessDisplay.Listener wirelessListener = new WirelessDisplay.Listener() {
        @Override public void onWirelessChanged() {
            audioRoute.setWireless(wireless.capturesSound());
            updateStatus();
            if (wireless.isActive()) stopAutoReceiver();
            else startAutoReceiver();
            if (wireless.inputSource() != null && !dimmed) bumpIdle();
            if (chooser != null && chooser.getVisibility() == View.VISIBLE) updateChooserDisplay();
        }
        @Override public void onWirelessDisplay(int id) {
            // 系统的回调可能在 XDesk 知道这块显示器之前就来了，那时被当成别人的虚拟显示器忽略掉
            if (!realDisplays.contains(id)) displayListener.onDisplayAdded(id);
        }
        @Override public void onWirelessPairing(String receiverName, boolean wrongBefore, WirelessDisplay.Pairing reply) {
            String note = getString(wrongBefore ? R.string.xd_wireless_code_wrong : R.string.xd_wireless_code_note, receiverName);
            ui().input(getString(R.string.xd_wireless_pair_title, receiverName), note, "0000", null,
                InputType.TYPE_CLASS_NUMBER, getString(R.string.xd_wireless_pair), reply::answer, () -> reply.answer(null));
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
            s.dangerRow(c, getString(R.string.xd_wireless_stop, wireless.receiverName()), null, () -> {
                d[0].dismiss();
                wireless.forgetAutoReceiver();
                wireless.stop();
            });
        }
        s.switchRow(s.card(c), getString(R.string.xd_clipboard_sync), getString(R.string.xd_clipboard_sync_note), wireless.clipboardSyncOn(),
            on -> wireless.setClipboardSync(on));
        final LinearLayout found = s.card(c);
        final TextView searching = Style.label(this, getString(R.string.xd_wireless_searching), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        searching.setPadding(dp(12), dp(12), dp(12), dp(12));
        found.addView(searching);
        final java.util.Set<String> shown = new java.util.HashSet<>();
        LinearLayout manual = s.card(c);
        s.valueRow(manual, getString(R.string.xd_wireless_address), getString(R.string.xd_wireless_address_note), "", () -> {
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
                }, null);
        });
        final java.util.List<ReceiverRow> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
        final boolean[] open = {true};
        pageNote(c, R.string.xd_wireless_picker_note);
        d[0] = settingsPage(getString(R.string.xd_wireless), c, () -> {
            open[0] = false;
            finder.stop();
            if (settingsRefresh != null) settingsRefresh.run();
        });
        // What the network announced may be long gone: a row shows only while its receiver answers
        final Runnable refreshSearching = () -> {
            boolean any = false;
            for (ReceiverRow row : rows) any |= row.view.getVisibility() == View.VISIBLE;
            searching.setVisibility(any ? View.GONE : View.VISIBLE);
        };
        new Thread(() -> {
            while (open[0]) {
                for (ReceiverRow row : rows) probeReceiver(row, refreshSearching);
                SystemClock.sleep(RECEIVER_PROBE_MS);
            }
        }, "xdesk-receiver-probe").start();
        finder.start(r -> {
            // Anyone can announce an id: one that is already listed must not hide this address, or the other way round
            String key = r.id + "@" + r.host;
            if (!shown.add(key)) return;
            int at = found.getChildCount();
            s.valueRow(found, r.name, r.host, "", () -> {
                closeSettings();
                startWireless(r.host, r.port, r.name);
            });
            View view = found.getChildAt(at);
            view.setVisibility(View.GONE);
            ReceiverRow row = new ReceiverRow(r.host, r.port, view);
            rows.add(row);
            new Thread(() -> probeReceiver(row, refreshSearching), "xdesk-receiver-probe").start();
        });
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
                    startWireless(r.host, r.port, r.name);
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
        if (!apps.injectorReady()) {
            Toast.makeText(this, R.string.xd_wireless_need_injector, Toast.LENGTH_LONG).show();
            return;
        }
        wireless.start(host, port, name);
    }

    // --- 网页显示 ---

    /** The browser's mouse and keyboard, as the phone's touchpad and keyboard would. */
    private final InputSink webInput = new InputSink() {
        @Override public void move(int dx, int dy) { if (client != null) client.move(dx, dy); }
        @Override public void moveToFraction(float x, float y) { if (client != null) client.moveToFraction(x, y); }
        @Override public void pointerGone() {
            // Whichever it was on, the client may have changed since
            session.input().pointerGone();
            apps.input().pointerGone();
            apps.browserGone();
            updateStatus();
        }
        @Override public boolean anyThread() {
            return apps.isActive();
        }
        @Override public void androidButton(int button) {
            if (button == 1) apps.sendBack();
            else if (button == 2) goHomeOnDisplay();
            else if (button == 3) wireless.sendApps(appLines());
        }
        @Override public void chooseApp(String key) {
            chooseKey(key);
        }
        @Override public void browserLayout(String layout) {
            apps.setBrowserLayout(layout);
            updateStatus();
        }
        @Override public void button(int b, boolean down) { if (client != null) client.button(b, down); }
        @Override public void click(int b) { if (client != null) client.click(b); }
        @Override public void scroll(int dy, int dx) { if (client != null) client.scroll(dy, dx); }
        @Override public void key(int scan, boolean down) { if (client != null) client.key(scan, down); }
        @Override public void tapKey(int scan) { if (client != null) client.tapKey(scan); }
        @Override public void text(String s) { if (client != null) client.text(s); }
    };

    private WirelessFinder autoFinder;
    private boolean activityStarted;
    private long lastAutoConnect = -AUTO_CONNECT_PAUSE_MS;
    private static final int AUTO_CONNECT_PAUSE_MS = 10000;
    private static final int RECEIVER_PROBE_MS = 2500, RECEIVER_PROBE_TIMEOUT_MS = 1200;

    private final WebServer.Listener webListener = new WebServer.Listener() {
        @Override public void onWebCode(String browserName, String code) {
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
            s.dangerRow(c, getString(R.string.xd_wireless_stop, wireless.receiverName()), null, () -> {
                d[0].dismiss();
                wireless.stop();
            });
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
                s.valueRow(where, a.url(), kind, getString(R.string.xd_web_copy), () -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("XDesk", a.url()));
                    Toast.makeText(this, getString(R.string.xd_web_copied, a.url()), Toast.LENGTH_SHORT).show();
                });
            }
        }
        LinearLayout wired = s.section(c, getString(R.string.xd_web_wired));
        s.infoRow(wired, getString(R.string.xd_web_wired_how), getString(R.string.xd_web_wired_how_note), null);
        s.valueRow(wired, getString(R.string.xd_web_ncm), getString(R.string.xd_web_ncm_note),
            getString(WebServer.usbTetheringIsNcm(this) ? R.string.xd_web_ncm_on : R.string.xd_web_ncm_off), () -> {
                if (WebServer.usbTetheringIsNcm(this)) return;
                if (WebServer.setUsbTetheringNcm(this)) {
                    Toast.makeText(this, R.string.xd_web_ncm_done, Toast.LENGTH_LONG).show();
                    d[0].dismiss();
                    ui.postDelayed(this::showWebPage, 300);
                } else {
                    s.confirm(getString(R.string.xd_web_ncm), getString(R.string.xd_web_ncm_need_permission), getString(R.string.xd_web_copy), () -> {
                        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb shell settings put global tether_force_usb_functions 1"));
                    });
                }
            });
        s.valueRow(wired, getString(R.string.xd_web_tethering), getString(R.string.xd_web_tethering_note), "", this::openTetherSettings);
        LinearLayout computer = s.section(c, getString(R.string.xd_web_computer));
        s.infoRow(computer, getString(R.string.xd_web_wired_how), getString(R.string.xd_web_computer_note), null);
        addCopyRow(s, computer, getString(R.string.xd_web_adb), "adb forward tcp:" + WebServer.PORT + " tcp:" + WebServer.PORT);
        java.util.List<WebServer.Address> forSsh = WebServer.addresses();
        if (!forSsh.isEmpty()) {
            addCopyRow(s, computer, getString(R.string.xd_web_ssh),
                "ssh -N -L " + WebServer.PORT + ":127.0.0.1:" + WebServer.PORT + " -p 8022 " + forSsh.get(0).host);
        }
        int paired = web.pairedCount();
        if (paired > 0) {
            s.dangerRow(c, getString(R.string.xd_web_forget, paired), null, () ->
                s.confirm(getString(R.string.xd_web_forget_title), getString(R.string.xd_web_forget_message), getString(R.string.xd_web_forget_action), () -> {
                    web.forgetBrowsers();
                    if (wireless.isWeb() && wireless.isActive()) wireless.stop();
                    d[0].dismiss();
                }));
        }
        pageNote(c, R.string.xd_web_note);
        d[0] = settingsPage(getString(R.string.xd_web), c, () -> {
            if (settingsRefresh != null) settingsRefresh.run();
        });
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
            if (session.stopping() != null) {
                Toast.makeText(XDeskActivity.this, R.string.xd_desktop_stopping, Toast.LENGTH_SHORT).show();
                return;
            }
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
            if (contentKey != null) leaveContent();
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
                ui().confirm(getString(R.string.xd_switch_desktop_title), getString(R.string.xd_switch_desktop, runningSession), getString(R.string.xd_switch), new Runnable() {
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
        @Override public void onContentChosen(String key) {
            chooseContent(key);
        }
        @Override public void onAppChosen(AppCatalog.App app) {
            if (contentKey != null) leaveContent();
            chooseApp(app);
        }
        @Override public void onNoDisplayHint() {
            showWirelessPicker();
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
        Contents.setHost(contentsHost);
        // Paired Macs as contents (MacSource): reads the saved list, connects nothing yet
        MacSource.start(this);
        MacSource.switcher = prefix -> showSwitcher(true);
        inputs = (InputManager) getSystemService(INPUT_SERVICE);
        ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        cover = new ExternalCover(this);
        prefs = getPreferences(MODE_PRIVATE);
        session = DesktopSession.get(this);
        apps = AppSession.get(this);
        catalog = AppCatalog.get(this);
        ExternalLauncher.setHost(launcherHost);
        audioRoute = AudioRoute.get(this);
        wireless = WirelessDisplay.get(this);
        externalDock = new ExternalDock(this, () -> apps.raiseCursor(), id -> apps.imeTop(id));
        apps.setBottomEdgeListener(externalDock);
        if (prefs.contains(PREF_OUTPUT)) audioRoute.migrateOldChoice(prefs.getInt(PREF_OUTPUT, 0));
        applyOrientation();
        lastOrientation = getResources().getConfiguration().orientation;
        setContentView(buildUi());
        screens.getViewTreeObserver().addOnGlobalLayoutListener(this::fitToKeyboard);
        session.setListener(sessionListener);
        apps.setListener(appListener);
        audioRoute.setListener(audioListener);
        wireless.setListener(wirelessListener);
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
        activityStarted = false;
        stopAutoReceiver();
        Display d = externalDisplay();
        if (d == null || TargetDisplay.isCar(d) || !prefs.getBoolean(PREF_COVER, true) || !(apps.isActive() || session.isActive())) return;
        cover.show(d);
        if (cover.isShown()) {
            forwardingBeforeCover = apps.isForwarding();
            if (forwardingBeforeCover) apps.setForwarding(false);
            // 盖着的桌面不该收到打的字
            takenBeforeCover = desktopTaken;
            if (takenBeforeCover) setDesktopTaken(false);
            apps.setHotkeyAllowed(false);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        removeCover();
        activityStarted = true;
        startAutoReceiver();
    }

    private void removeCover() {
        if (!cover.isShown()) return;
        cover.hide();
        apps.setHotkeyAllowed(true);
        if (forwardingBeforeCover) apps.setForwarding(true);
        forwardingBeforeCover = false;
        if (takenBeforeCover && session.isActive()) setDesktopTaken(true);
        takenBeforeCover = false;
        if (phonePassive) refocusExternal();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (Contents.host() == contentsHost) Contents.setHost(null);
        // onStop took the hotkey and the input away for the cover. The session outlives this window, so give them back
        removeCover();
        if (externalDock != null) externalDock.setDisplay(null);
        if (ExternalLauncher.host() == launcherHost) ExternalLauncher.setHost(null);
        ui.removeCallbacks(retakeDesktop);
        // 换语言等会重建这个窗口，新的不知道桌面被接管了：先交还，键盘焦点和输入法回手机
        if (desktopTaken) {
            desktopTaken = false;
            MainActivity.setTakeMouse(false);
            Display d = desktopDisplay();
            if (d != null) apps.setImeOnDisplay(d.getDisplayId(), false);
        }
        displays.unregisterDisplayListener(displayListener);
        CarActivity.setListener(null);
        inputs.unregisterInputDeviceListener(deviceListener);
        ui.removeCallbacksAndMessages(null);
        session.setListener(null);
        apps.setListener(null);
        audioRoute.setListener(null);
        wireless.setListener(null);
        wireless.setWebInput(null);
        // The server stays: this window is recreated on a language change. Without a listener it turns browsers away
        web.setListener(null);
        if (webCodeDialog != null) webCodeDialog.dismiss();
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

        setupBack = Style.button(this, getString(R.string.xd_back_to_launcher), Style.SURFACE_RAISED, 14, 24);
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
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setPadding(dp(8), 0, dp(8), 0);
        buildTopButtons();
        runningDock = new DockBar(this, chooserHost, false);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (landscape) {
            // 横屏高度最值钱：状态、收藏和按钮共一行
            topBar = top;
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
        full.addView(center, new FrameLayout.LayoutParams(-1, -1));
        fullscreenBtn.setVisibility(View.GONE);
        addCornerIcon(full, R.drawable.xd_ic_close, getString(R.string.xd_exit_fullscreen), Gravity.TOP | Gravity.END, new Runnable() {
            @Override public void run() { setFullscreenWanted(false); }
        });
        if (!apps.isActive()) {
            addCornerIcon(full, R.drawable.xd_ic_keyboard, getString(R.string.xd_keyboard), Gravity.BOTTOM | Gravity.END, new Runnable() {
                @Override public void run() { toggleKeyboard(); }
            });
        }
        if (apps.isActive()) {
            addCornerIcon(full, R.drawable.xd_ic_back, getString(R.string.xd_back_app), Gravity.TOP | Gravity.START, new Runnable() {
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
        touchBtn = new Style.IconButton(this, R.drawable.xd_ic_pointer, getString(R.string.xd_mode_mouse_short), Style.SURFACE);
        touchBtn.onTap(new Runnable() {
            @Override public void run() {
                apps.toggleTouchMode();
                updateStatus();
            }
        });
        forwardBtn = new Style.IconButton(this, R.drawable.xd_ic_mouse, getString(R.string.xd_take_over), Style.SURFACE);
        forwardBtn.onTap(new Runnable() {
            @Override public void run() {
                if (apps.isActive()) apps.setForwarding(!apps.isForwarding());
                else setDesktopTaken(!desktopTaken);
            }
        });
        launcherBtn = new Style.IconButton(this, R.drawable.xd_ic_apps, getString(R.string.xd_launcher), Style.SURFACE);
        launcherBtn.onTap(new Runnable() {
            @Override public void run() { openChooserOverSession(); }
        });
        settingsBtn = new Style.IconButton(this, R.drawable.xd_ic_settings, getString(R.string.xd_settings), Style.SURFACE);
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
        combos.addView(keyButton(getString(R.string.xd_key_select_all), SCAN_A), keyParams());
        combos.addView(keyButton(getString(R.string.xd_key_cut), SCAN_X), keyParams());
        combos.addView(keyButton(getString(R.string.xd_key_copy), SCAN_C), keyParams());
        combos.addView(keyButton(getString(R.string.xd_key_paste), SCAN_V), keyParams());
        combos.addView(keyButton(getString(R.string.xd_key_undo), SCAN_Z), keyParams());
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
        // 实体键盘打字时输入法只露出一条候选栏，也算 IME 可见：那时顶部一行留着，否则收起后就一直回不来
        boolean open = w != null && w.isVisible(WindowInsets.Type.ime())
            && w.getInsets(WindowInsets.Type.ime()).bottom >= getWindow().getDecorView().getHeight() * SOFT_KEYBOARD_PART;
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
        boolean passive = (apps.isActive() || (desktopTaken && session.isActive())) && chooser.getVisibility() != View.VISIBLE
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

    /** 只在外接屏的安卓应用上有：Linux 桌面的鼠标由窗口独占，车机没有鼠标。 */
    private void updateExternalDock() {
        if (externalDock == null) return;
        Display d = apps.isActive() && !session.isActive() && prefs.getBoolean(PREF_EXTERNAL_DOCK, true) && Settings.canDrawOverlays(this) ? externalDisplay() : null;
        externalDock.setDisplay(d != null && !TargetDisplay.isCar(d) ? d : null);
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
        // 正常时不显示：应用图标和按钮已经说明了在用什么、键鼠在哪
        int v = color == Style.OK ? View.GONE : View.VISIBLE;
        statusText.setVisibility(v);
        statusDot.setVisibility(v);
        if (statusRow != null) statusRow.setVisibility(v);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        statusDot.setBackground(d);
    }

    /** 接管在两种模式下都有：应用模式抓实体键鼠，桌面模式把键盘焦点和输入法放到外接屏上。 */
    private void applyModeToTopBar() {
        boolean app = apps.isActive();
        updateExternalDock();
        // 应用接手了外接屏：桌面的接管不再有意义，输入法也放回去
        if (desktopTaken && app) takeDesktopWhenBack = true;
        if (desktopTaken && (app || !session.isActive())) setDesktopTaken(false);
        updatePhoneFocus();
        forwardBtn.setVisibility(app || session.isActive() ? View.VISIBLE : View.GONE);
        if (!app) {
            forwardBtn.setLabel(getString(desktopTaken ? R.string.xd_give_back : R.string.xd_take_over));
            forwardBtn.setActive(desktopTaken);
        }
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
        if (wireless != null) wireless.setTouchMode(touch);
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
        forwardBtn.setLabel(getString(apps.isForwarding() ? R.string.xd_give_back : R.string.xd_take_over));
        forwardBtn.setActive(apps.isForwarding());
        boolean touch = apps.isTouchMode();
        touchBtn.setLabel(getString(touch ? R.string.xd_mode_touch : R.string.xd_mode_mouse_short));
        touchBtn.setIcon(touch ? R.drawable.xd_ic_touch : R.drawable.xd_ic_pointer);
        touchBtn.setActive(touch);
        if (apps.hasInjector() && apps.hasDisplay() && apps.app() == null) {
            setStatus(Style.WARN, getString(R.string.xd_status_no_app));
        } else if (!apps.hasInjector()) {
            setStatus(Style.DANGER, getString(apps.usesShell() ? R.string.xd_status_injector_gone_shell : R.string.xd_status_injector_gone_shizuku));
        } else if (!apps.hasDisplay()) {
            setStatus(Style.WARN, getString(R.string.xd_status_display_gone_app, apps.label()));
        } else if (wireless.reconnecting()) {
            setStatus(Style.WARN, getString(R.string.xd_status_reconnecting, wireless.inputSource()));
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
        if (wireless != null) wireless.setDesktopShown(session.isConnected() && !apps.isActive());
        audioRoute.setCar(onCar());
        applyFullscreen();
        applyModeToTopBar();
        if (apps.isActive()) {
            updateAppStatus();
            return;
        }
        if (session.isConnected() && desktopTaken) setStatus(Style.OK, getString(R.string.xd_status_desktop_taken, desktopName != null ? desktopName : ""));
        else if (session.isConnected()) setStatus(Style.OK, desktopName != null ? getString(onCar() ? R.string.xd_status_desktop_car : R.string.xd_status_desktop_display, desktopName) : getString(R.string.xd_status_desktop_connected));
        else setStatus(Style.WARN, getString(R.string.xd_status_desktop_not_connected));
    }

    private void showSetup(String title, String message, boolean progress, String action, View.OnClickListener onAction) {
        chooser.setVisibility(View.GONE);
        chooser.setNotice(null);
        displayNoticeShown = false;
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
        bumpIdle();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (apps.isActive()) apps.phoneTouched();
            // 摸手机屏会把焦点拿到手机上，设置页这类窗口关掉后焦点也留在手机屏
            if (phonePassive) refocusExternal();
        }
        return super.dispatchTouchEvent(e);
    }

    private void bumpIdle() {
        ui.removeCallbacks(dim);
        if (dimmed) setDimmed(false);
        if ((session.isActive() || apps.isActive()) && prefs.getBoolean(PREF_DIM, true)) ui.postDelayed(dim, wireless.inputSource() != null ? DIM_WITH_COMPUTER_MS : DIM_AFTER_MS);
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
            Toast.makeText(this, R.string.xd_output_car, Toast.LENGTH_LONG).show();
            return;
        }
        // One setting for a cable, a wireless and a web display: system default follows the display, see AudioRoute
        final boolean receiver = wireless.isActive();
        final List<AudioRoute.Output> outputs = audioRoute.outputs();
        String[] labels = new String[outputs.size() + 1];
        labels[0] = receiver ? getString(R.string.xd_output_system_receiver, wireless.receiverName()) : getString(R.string.xd_output_system);
        String selected = audioRoute.selectedKey();
        int checked = selected.isEmpty() ? 0 : -1;
        int selectedType = selected.isEmpty() ? -1 : Integer.parseInt(selected.substring(0, selected.indexOf('|')));
        for (int i = 0; i < outputs.size(); i++) {
            AudioRoute.Output o = outputs.get(i);
            labels[i + 1] = o.label;
            if (o.key.equals(selected) || (checked < 0 && o.type == selectedType)) checked = i + 1;
        }
        String note = getString(R.string.xd_output_note_follow) + "\n"
            + getString(apps.injectorReady() ? R.string.xd_output_note_shared : R.string.xd_output_note_desktop_only)
            + "\n" + getString(R.string.xd_output_note_unverified);
        outputDialog = ui().choice(getString(R.string.xd_output_title), note, labels, checked, which -> {
            audioRoute.select(which == 0 ? AudioRoute.KEY_SYSTEM : outputs.get(which - 1).key);
            if (audioRoute.isMissing()) setStatus(Style.WARN, getString(R.string.xd_output_missing));
        });
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
        setRequestedOrientation(ORIENTATIONS[prefs.getInt(PREF_ORIENTATION, ORIENTATION_DEFAULT)]);
    }

    private int widthIndex() {
        int w = prefs.getInt(PREF_WIDTH, DEFAULT_WIDTH);
        for (int i = 0; i < WIDTHS.length; i++) if (WIDTHS[i] == w) return i;
        return 1;
    }

    private void chooseResolution(final TextView value) {
        ui().choice(getString(R.string.xd_resolution), null, strings(WIDTH_LABELS), widthIndex(), which -> {
            prefs.edit().putInt(PREF_WIDTH, WIDTHS[which]).apply();
            Display d = externalDisplay();
            if (d != null) applySize(d);
            value.setText(WIDTH_LABELS[which]);
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
    }

    private void showSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout device = s.card(c);
        s.valueRow(device, getString(R.string.xd_section_phone), null, "", this::showPhoneSettings);
        s.valueRow(device, getString(R.string.xd_section_input), null, "", this::showInputSettings);
        s.valueRow(device, getString(R.string.xd_section_sound), null, audioRoute.shortLabel(), this::showSoundSettings);
        s.valueRow(device, getString(R.string.xd_section_display), null, "", this::showDisplaySettings);
        LinearLayout connect = s.section(c, getString(R.string.xd_section_connect));
        s.valueRow(connect, getString(R.string.xd_mac_settings), getString(R.string.xd_mac_settings_note), "", () -> MacSettings.open(this));
        final TextView[] receiver = new TextView[1], browser = new TextView[1];
        receiver[0] = s.valueRow(connect, getString(R.string.xd_wireless), getString(R.string.xd_wireless_note),
            wireless.isActive() ? wireless.receiverName() : getString(R.string.xd_wireless_off), this::showWirelessPicker);
        browser[0] = s.valueRow(connect, getString(R.string.xd_web), getString(R.string.xd_web_row_note), webValue(), this::showWebPage);
        settingsRefresh = () -> {
            receiver[0].setText(wireless.isActive() ? wireless.receiverName() : getString(R.string.xd_wireless_off));
            browser[0].setText(webValue());
        };
        LinearLayout more = s.section(c, getString(R.string.xd_section_more));
        s.valueRow(more, getString(R.string.xd_x11_settings), getString(R.string.xd_x11_settings_note), "",
            this::showX11Settings);
        s.valueRow(more, getString(R.string.xd_section_about), null, versionName(), this::showAboutSettings);

        if (session.isActive()) {
            s.dangerRow(c, getString(R.string.xd_end_desktop_row), getString(R.string.xd_end_desktop_row_note), () -> {
                closeSettings();
                confirmStop();
            });
        }
        if (DesktopService.isRunning()) {
            s.dangerRow(c, getString(R.string.xd_end_xdesk), getString(R.string.xd_end_xdesk_note), () -> {
                closeSettings();
                confirmEndXDesk();
            });
        }
        settingsPage(getString(R.string.xd_settings), c, () -> settingsRefresh = null);
    }

    private void showPhoneSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout show = s.card(c);
        int orientation = 2 - prefs.getInt(PREF_ORIENTATION, ORIENTATION_DEFAULT);
        s.segmentedRow(show, getString(R.string.xd_orientation), null, strings(ORIENTATION_LABELS), orientation, which -> {
            prefs.edit().putInt(PREF_ORIENTATION, ORIENTATION_VALUES[which]).apply();
            applyOrientation();
        });
        s.switchRow(show, getString(R.string.xd_dim), getString(R.string.xd_dim_note), prefs.getBoolean(PREF_DIM, true), on -> {
            prefs.edit().putBoolean(PREF_DIM, on).apply();
            bumpIdle();
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

    private void showInputSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        boolean appMode = apps.isActive();
        LinearLayout input = s.card(c);
        s.percentRow(input, getString(R.string.xd_pad_speed), null, Math.round(prefs.getFloat(PREF_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            touchpad.setSpeed(v);
            directTouch.setSpeed(v);
            prefs.edit().putFloat(PREF_SPEED, v).apply();
        });
        s.percentRow(input, getString(R.string.xd_scroll_speed), getString(R.string.xd_scroll_speed_note), Math.round(prefs.getFloat(PREF_SCROLL_SPEED, 1f) * 100), p -> {
            float v = p / 100f;
            touchpad.setScrollSpeed(v);
            prefs.edit().putFloat(PREF_SCROLL_SPEED, v).apply();
        });
        if (appMode) {
            s.percentRow(input, getString(R.string.xd_mouse_speed), getString(R.string.xd_mouse_speed_note), Math.round(prefs.getFloat(PREF_MOUSE_SPEED, 1f) * 100), p -> {
                float v = p / 100f;
                prefs.edit().putFloat(PREF_MOUSE_SPEED, v).apply();
                apps.setMouseSpeed(v);
            });
            s.switchRow(input, getString(R.string.xd_always_cursor), getString(R.string.xd_always_cursor_note), prefs.getBoolean(PREF_ALWAYS_CURSOR, false), on -> {
                prefs.edit().putBoolean(PREF_ALWAYS_CURSOR, on).apply();
                apps.setAlwaysShowCursor(on);
            });
        }
        settingsPage(getString(R.string.xd_section_input), c, null);
    }

    private void showSoundSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sound = s.card(c);
        settingsOutputValue = s.valueRow(sound, getString(R.string.xd_output), null, audioRoute.shortLabel(), this::chooseOutput);
        settingsPage(getString(R.string.xd_section_sound), c, () -> settingsOutputValue = null);
    }

    private void showDisplaySettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        boolean appMode = apps.isActive();
        LinearLayout external = s.card(c);
        addDensitySetting(s, external);
        if (AppSession.fullscreenSupported()) {
            s.switchRow(external, getString(R.string.xd_fullscreen_default), getString(R.string.xd_fullscreen_default_note), apps.isFullscreenDefault(), on -> apps.setFullscreenDefault(on));
            s.valueRow(external, getString(R.string.xd_fullscreen_reset), getString(R.string.xd_fullscreen_reset_note), "", () ->
                s.confirm(getString(R.string.xd_fullscreen_reset_title), getString(R.string.xd_fullscreen_reset_message), getString(R.string.xd_restore), () -> {
                    if (!apps.resetFullscreen()) Toast.makeText(this, R.string.xd_fullscreen_reset_need_injector, Toast.LENGTH_LONG).show();
                }));
        }
        s.switchRow(external, getString(R.string.xd_external_dock), getString(R.string.xd_external_dock_note), prefs.getBoolean(PREF_EXTERNAL_DOCK, true), on -> {
            prefs.edit().putBoolean(PREF_EXTERNAL_DOCK, on).apply();
            updateExternalDock();
        });
        String cover = getString(Settings.canDrawOverlays(this) ? R.string.xd_cover_note : R.string.xd_cover_note_no_overlay);
        s.switchRow(external, getString(R.string.xd_cover), cover, prefs.getBoolean(PREF_COVER, true), on -> {
            prefs.edit().putBoolean(PREF_COVER, on).apply();
        });
        if (!appMode) {
            final TextView[] resolution = new TextView[1];
            resolution[0] = s.valueRow(external, getString(R.string.xd_resolution), getString(R.string.xd_resolution_note), getString(WIDTH_LABELS[widthIndex()]), () -> chooseResolution(resolution[0]));
        }
        settingsPage(getString(R.string.xd_section_display), c, null);
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
        s.percentRow(card, getString(R.string.xd_x11_mouse_speed), null, p.capturedPointerSpeedFactor.get(), v -> {
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
        LinearLayout advanced = s.section(c, getString(R.string.xd_section_more));
        s.valueRow(advanced, getString(R.string.xd_x11_all), getString(R.string.xd_x11_all_note), "",
            () -> startActivity(new Intent(this, LoriePreferences.class)));
        settingsPage(getString(R.string.xd_x11_settings), c, null);
    }

    private void showAboutSettings() {
        final SettingsUi s = ui();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout about = s.card(c);
        s.infoRow(about, "XDesk", null, versionName());
        s.switchRow(about, getString(R.string.xd_diag_setting), getString(R.string.xd_diag_setting_note), prefs.getBoolean(PREF_DIAG, false), on -> {
            prefs.edit().putBoolean(PREF_DIAG, on).apply();
            applyDiagVisibility();
        });
        final ShellActivation shell = apps.shell();
        if (shell.isPaired() || shell.isRunning()) {
            s.infoRow(about, getString(R.string.xd_builtin), getString(R.string.xd_builtin_note), shellState(shell));
            if (shell.isRunning()) {
                s.valueRow(about, getString(R.string.xd_builtin_stop), getString(R.string.xd_builtin_stop_note), "", () ->
                    s.confirm(getString(R.string.xd_builtin_stop_title), getString(R.string.xd_builtin_stop_message), getString(R.string.xd_stop), () -> {
                        closeSettings();
                        stopShellServer(null);
                    }));
            }
            s.valueRow(about, getString(R.string.xd_pair_again), getString(R.string.xd_pair_again_note), "", () ->
                s.confirm(getString(R.string.xd_pair_again_title), getString(R.string.xd_pair_again_message), getString(R.string.xd_pair_again), () -> {
                    closeSettings();
                    stopShellServer(() -> {
                        shell.forgetPairing();
                        retryRequested = true;
                        activationFlow = true;
                        showActivation(null);
                    });
                }));
        }
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
            if (session.isActive() && !desktopTaken && chooser.getVisibility() != View.VISIBLE && hasExternalMouse()) keyView.requestPointerCapture();
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
        if (contentKey != null) return contentKey;
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
        ExternalLauncher.changed();
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
        TextView sub = Style.label(this, getString(onDisplay ? (onCar() ? R.string.xd_on_car : R.string.xd_card_on_display) : app != null ? R.string.xd_android_app : R.string.xd_linux_desktop),
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
        star.setContentDescription(getString(R.string.xd_chooser_favourites));
        head.addView(star, new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET)));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        // 收藏里的位置：只有在收藏里才显示
        final LinearLayout order = s.card(c);
        LinearLayout orderRow = new LinearLayout(this);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(dp(12), dp(6), dp(8), dp(6));
        orderRow.addView(Style.label(this, getString(R.string.xd_card_position), Style.TEXT, Style.TEXT_BODY), new LinearLayout.LayoutParams(0, -2, 1));
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
            s.groupLabel(c, getString(R.string.xd_card_display));
            LinearLayout display = s.card(c);
            s.inlineNumberRow(display, getString(R.string.xd_density), global > 0 ? getString(R.string.xd_card_density_note_value, global) : getString(R.string.xd_card_density_note),
                global > 0 ? String.valueOf(global) : getString(R.string.xd_chooser_default), apps.appDensity(app), DENSITY_MIN, DENSITY_MAX,
                dpi -> apps.setAppDensity(app, dpi), () -> apps.setAppDensity(app, 0));
            if (AppSession.fullscreenSupported()) {
                s.segmentedRow(display, getString(R.string.xd_card_fullscreen), getString(apps.isFullscreenDefault() ? R.string.xd_card_fullscreen_note_on : R.string.xd_card_fullscreen_note_off),
                    strings(R.string.xd_card_follow_default, R.string.xd_on, R.string.xd_off), apps.appFullscreen(app), which -> apps.setAppFullscreen(app, which));
            }
            if (AppSession.touchModeAllowed(app)) {
                s.groupLabel(c, getString(R.string.xd_section_input));
                LinearLayout input = s.card(c);
                s.segmentedRow(input, getString(R.string.xd_card_mode), getString(R.string.xd_card_mode_note), strings(R.string.xd_mode_mouse, R.string.xd_mode_touch), apps.isTouchMode(app) ? 1 : 0, which -> {
                    apps.setTouchMode(app, which == 1);
                    updateStatus();
                });
            }
            if (apps.canCloseApps()) {
                TextView close = Style.button(this, getString(R.string.xd_card_close), Style.SURFACE, Style.TEXT_BODY, 16);
                close.setTextColor(Style.DANGER);
                close.setOnClickListener(v -> {
                    Style.tick(v);
                    s.confirm(getString(R.string.xd_card_close_title, app.label), getString(R.string.xd_unsaved_may_be_lost), getString(R.string.xd_close), () -> {
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

    // --- 外接屏上的切换器（DISPLAY.md §6） ---

    /** 切换器开着时输入交给它；关上时还给原来的。 */
    private InputSink beforeSwitcher;

    /**
     * 四指上滑、把手、Dock 打开所有系统的；三指上滑（总览）只列现在这个系统的。
     * 没有外接屏时打开手机上的列表。再调一次就关上。
     */
    void showSwitcher(boolean onlySystem) {
        Display d = externalDisplay();
        if (d == null) {
            showChooser();
            return;
        }
        if (SwitcherOverlay.isShown()) {
            SwitcherOverlay.hide(switcherHost);
            return;
        }
        beforeSwitcher = client;
        client = SwitcherOverlay.INPUT;
        SwitcherOverlay.show(this, d, switcherHost, onlySystem);
    }

    private final SwitcherOverlay.Host switcherHost = new SwitcherOverlay.Host() {
        @Override public List<String> running() {
            ArrayList<String> keys = new ArrayList<>();
            String current = currentKey(), previous = previousKey();
            if (current != null) keys.add(current);
            if (previous != null && ChooserView.isAppKey(previous)) keys.add(previous);
            for (ContentSource s : Contents.sources()) for (ContentSource.Entry e : s.windows()) keys.add(e.key);
            return keys;
        }
        @Override public List<String> favorites() { return savedLines(PREF_DOCK); }
        @Override public String current() { return currentKey(); }
        @Override public void open(String key) { chooseKey(key); }
        @Override public void closed() {
            if (client == SwitcherOverlay.INPUT) client = beforeSwitcher;
            beforeSwitcher = null;
        }
    };

    // --- 窗口交换机：Linux 程序、Mac 窗口这些内容（Contents，DISPLAY.md §1.5） ---

    /** 外接屏上现在的内容（"l:"、"m:" 开头的键）；是安卓应用或整个 Linux 桌面时为 null。 */
    private String contentKey;
    /** 等桌面连上以后要做的事（needDesktop），连上就做，失败就报给内容源。 */
    private Runnable desktopThen;
    private ContentSource.Result desktopFailed;
    private String desktopFor;

    private void chooseContent(final String key) {
        ContentSource s = Contents.sourceOf(key);
        if (s == null) return;
        Display d = externalDisplay();
        if (d == null) {
            needDisplayNotice();
            return;
        }
        rememberRecent(key);
        if (contentKey != null && !contentKey.equals(key)) leaveContent();
        s.open(key, d.getDisplayId(), contentResult);
    }

    /** 外接屏换成别的东西之前：告诉原来的内容源它被拿下了（不是退出）。 */
    private void leaveContent() {
        String key = contentKey;
        contentKey = null;
        ContentSource s = Contents.sourceOf(key);
        if (s != null) s.hidden(key);
    }

    private final ContentSource.Result contentResult = new ContentSource.Result() {
        @Override public void shown(String key, InputSink input) {
            contentKey = key;
            client = input != null ? input : session.input();
            closeChooserOverSession();
            showRunning();
            updateCapture();
            updateStatus();
            refreshDocks();
            bumpIdle();
        }
        @Override public void failed(String key, String reason) {
            Toast.makeText(XDeskActivity.this, reason, Toast.LENGTH_LONG).show();
            refreshDocks();
        }
    };

    private final Contents.Host contentsHost = new Contents.Host() {
        @Override public int targetDisplayId() {
            Display d = externalDisplay();
            return d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        }
        @Override public void needDesktop(Runnable then, ContentSource.Result failed, String key) {
            if (session.isActive() && session.isConnected()) {
                then.run();
                return;
            }
            desktopThen = then;
            desktopFailed = failed;
            desktopFor = key;
            if (runningSession != null && !session.isActive()) {
                Display d = externalDisplay();
                if (d != null) attachDisplay(d);
                desktopName = runningSession;
            } else if (!session.isActive()) {
                launchDesktop(null, false);
            }
        }
        @Override public void freeDisplay() {
            releaseLatches();
            if (session.isActive()) leaveDesktop();
            apps.suspend();
        }
        @Override public void contentChanged() {
            chooser.refreshContents();
            refreshDocks();
        }
    };

    /** 桌面连上了：先做等着的事。 */
    private void desktopConnected() {
        Runnable then = desktopThen;
        desktopThen = null;
        desktopFailed = null;
        if (then != null) then.run();
    }

    /** 桌面没能连上：等着的内容报失败。 */
    private void desktopFailedToConnect() {
        ContentSource.Result failed = desktopFailed;
        String key = desktopFor;
        desktopThen = null;
        desktopFailed = null;
        if (failed != null) failed.failed(key, getString(R.string.xd_content_no_desktop));
    }

    private void chooseKey(String key) {
        if (Contents.sourceOf(key) != null) {
            chooseContent(key);
            return;
        }
        if (contentKey != null) leaveContent();
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
            @Override public void onFailure(String message) {
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
        if (d != null && displayNoticeShown) {
            displayNoticeShown = false;
            chooser.setNotice(null);
        }
        chooser.setDisplayConnected(d != null, TargetDisplay.isCar(d));
        audioRoute.setCar(onCar());
    }

    /** 要外接屏才能做的事：在启动器上一直说着，接上屏为止。 */
    private void needDisplayNotice() {
        if (chooser.getVisibility() != View.VISIBLE) {
            if (apps.isActive() || session.isActive()) openChooserOverSession();
            else showChooser();
        }
        chooser.setNotice(getString(NO_DISPLAY));
        displayNoticeShown = true;
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
            setChooserTermux(new ArrayList<String>(), null, null, false, getString(R.string.xd_termux_not_installed));
            return;
        }
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            setChooserTermux(savedSessions(), null, null, false, getString(R.string.xd_termux_permission));
            return;
        }
        setChooserTermux(savedSessions(), null, runningSession, false, null);
        boolean sent = TermuxCommand.run(this, TERMUX_STATE_COMMAND, new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) { parseTermuxState(stdout); }
            @Override public void onFailure(String message) { termuxFailed(message); }
        });
        if (sent) ui.postDelayed(termuxNoReply, TERMUX_REPLY_TIMEOUT_MS);
        else termuxNoReply.run();
    }

    /** Termux 回了话但没列出来：不用再等超时，直接说为什么。环境用上次的。 */
    private void termuxFailed(String message) {
        ui.removeCallbacks(termuxNoReply);
        setChooserTermux(savedSessions(), null, runningSession, false, message);
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
        setChooserTermux(sessions, defaultName, running, x, sessions.isEmpty() ? getString(R.string.xd_termux_no_sessions) : null);
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
        takeDesktopWhenBack = false;
        ui.removeCallbacks(retakeDesktop);
        setDesktopTaken(false);
        apps.desktopEnded();
        releaseLatches();
        client = null;
        session.abandon();
        MainActivity m = MainActivity.getInstance("");
        if (m != null) m.finishAndRemoveTask();
        // 应用留在了桌面所在的那种屏上，车机上是它自己的显示器
        Display d = desktopDisplay();
        if (d != null && apps.resumeApp(d, prefs.getFloat(PREF_MOUSE_SPEED, 1f), prefs.getBoolean(PREF_ALWAYS_CURSOR, false))) {
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
        ui().confirm(getString(R.string.xd_end_xdesk_title), getString(R.string.xd_end_xdesk_message), getString(R.string.xd_end), new Runnable() {
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
            showSetup(getString(R.string.xd_overlay_title), getString(R.string.xd_overlay_message), false, getString(R.string.xd_open_settings), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                }
            });
        } else {
            closeActivation();
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
    private boolean askNotificationsOnce() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            || prefs.getBoolean(PREF_NOTIFICATIONS_ASKED, false)) return false;
        prefs.edit().putBoolean(PREF_NOTIFICATIONS_ASKED, true).apply();
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        return true;
    }

    /** 配对框开着时才有配对码，所以先把找端口的服务开起来，用户在系统设置里也能从通知输入。 */
    private void beginPairing() {
        if (askNotificationsOnce()) {
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
        if (problem != null) chooser.setNotice(problem);
        else if (shownShellProblem != null) chooser.setNotice(null);
        shownShellProblem = problem;
        updateActivationPrompt();
    }

    /** 用户选了 Shizuku，或者系统太旧用不了无线调试。 */
    private void showShizukuSetup() {
        int shizuku = apps.shizukuState();
        if (shizuku == AppSession.SHIZUKU_NOT_INSTALLED) {
            showSetup(getString(R.string.xd_shizuku_missing_title), getString(R.string.xd_shizuku_missing), false, getString(R.string.xd_shizuku_website), new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HOME))); }
            });
        } else if (shizuku == AppSession.SHIZUKU_NOT_RUNNING) {
            showSetup(getString(R.string.xd_shizuku_stopped_title), getString(R.string.xd_shizuku_stopped), false, getString(R.string.xd_open_shizuku), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent open = getPackageManager().getLaunchIntentForPackage(AppSession.SHIZUKU_PACKAGE);
                    if (open != null) startActivity(open);
                }
            });
        } else if (shizuku == AppSession.SHIZUKU_TOO_OLD) {
            showSetup(getString(R.string.xd_shizuku_old_title), getString(R.string.xd_shizuku_old), false, getString(R.string.xd_shizuku_website), new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HOME))); }
            });
        } else if (shizuku == AppSession.SHIZUKU_NO_PERMISSION) {
            if (apps.permissionDeniedForGood()) {
                showSetup(getString(R.string.xd_shizuku_denied_title), getString(R.string.xd_shizuku_denied), false, getString(R.string.xd_open_shizuku), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent open = getPackageManager().getLaunchIntentForPackage(AppSession.SHIZUKU_PACKAGE);
                        if (open != null) startActivity(open);
                    }
                });
            } else {
                showSetup(getString(R.string.xd_shizuku_ask_title), getString(R.string.xd_shizuku_ask), false, getString(R.string.xd_shizuku_ask_action), new View.OnClickListener() {
                    @Override public void onClick(View v) { apps.requestPermission(); }
                });
            }
        }
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
        LorieView.capFramerate(desktopOnCar ? d.getDisplayId() : Display.INVALID_DISPLAY, (int) AppSession.CAR_REFRESH_HZ);
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
        showSetup(getString(R.string.xd_reattach_title), getString(R.string.xd_reattach, runningSession), true, null, null);
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void showWaiting() {
        showSetup(getString(R.string.xd_waiting_title), getString(R.string.xd_waiting), false, getString(R.string.xd_waiting_action), new View.OnClickListener() {
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
            showSetup(getString(R.string.xd_no_termux_title), getString(R.string.xd_no_termux), false,
                getString(R.string.xd_no_termux_action), v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_DOWNLOAD))));
            addSetupChoice(getString(R.string.xd_back_to_launcher), v -> backToChooser());
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
        askNotificationsOnce();
        showSetup(getString(R.string.xd_starting_desktop_title), getString(R.string.xd_starting_desktop), true, null, null);
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
        showSetup(getString(R.string.xd_run_command_denied_title), getString(R.string.xd_run_command_denied), false, getString(R.string.xd_open_settings), new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
    }

    private void showConnectFailure() {
        desktopFailedToConnect();
        showSetup(getString(R.string.xd_desktop_no_start_title), getString(R.string.xd_desktop_no_start), false, getString(R.string.xd_retry), new View.OnClickListener() {
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
        if (chooser.getVisibility() == View.VISIBLE) refreshChooser();
    }

    private void confirmStop() {
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            ui().confirm(getString(R.string.xd_end_desktop_title), getString(R.string.xd_end_desktop_no_permission), getString(R.string.xd_ok), null);
            return;
        }
        ui().confirm(getString(R.string.xd_end_desktop_confirm_title), getString(R.string.xd_end_desktop_message), getString(R.string.xd_end), new Runnable() {
            @Override public void run() { stopDesktop(); }
        });
    }
}
