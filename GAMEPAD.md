# 虚拟手柄（GAMEPAD.md）

状态：已实现，随 0.6.3 发布（2026-11-18）。用户在 15 Ultra 上验收：外屏 GeForce NOW 里玩原神，操作正常（游戏认成手柄，按手机屏不抢焦点）。经 Codex 和 DeepSeek 审计，P0 在 15 Ultra 上实测了设备部分（§8）；焦点和 GeForce NOW 在测试版里验收。
来源：用户想在手机屏上放一个可以自己摆布局的虚拟手柄，外屏上的安卓游戏（含 GeForce NOW）把它当成真手柄。
相关：GAMING.md（游戏直通，键鼠给游戏）、INPUT.md §15、`UhidKeyboard.java`（同样的 uhid 做法）。布局模型借鉴 nx2android 的屏上手柄（`nxnative/dm/android/overlay_default.tsv`、`overlay_layout.h`）；设备身份和描述符同 scrcpy 3.1 的 uhid 手柄（`app/src/uhid/gamepad_uhid.c`、`app/src/hid/hid_gamepad.c`）。

## 1. 目标和范围

- 外屏（有线外屏、车机，或无线投到接收端）上是 XDesk 起的**安卓应用**时，手机屏可以变成一个虚拟手柄。
- 游戏看到的是一个**真的手柄设备**：`InputDevice` 的 source 含 `SOURCE_GAMEPAD | SOURCE_JOYSTICK`，按键是 `KEYCODE_BUTTON_*`，摇杆和扳机是 `MotionEvent` 的轴，和插了一个 USB 手柄走同一条路。
- 布局可以自己调：拖动、缩放、隐藏控件、透明度；存成多套，按应用自动切换。
- **只支持安卓游戏**（用户 2026-11-18 定）。X 程序和 Hangover 里的 Windows 游戏在容器里，看不到 `/dev/input`，不做。
- 无线接收端：游戏跑在手机上（XDesk 给它建的显示器），接收端只显示画面，这时可以用。接收端自己的手柄、网页的 Gamepad API 不在本次范围。
- GeForce NOW：它把 Android 认出的手柄转发给云端（GAMING.md §6 实测蓝牙手柄可用）。虚拟手柄在 Android 看来是一个 Xbox 360 有线手柄，应当一样能用；测试版验收（§10）。

不做：振动（hid-generic 不建 force feedback，§4）、陀螺仪、连发、宏、竖屏手柄。

## 2. 为什么用 uhid

- 注入服务以 shell 身份（uid 2000，经 Shizuku 或 ShellServer）运行，能写 `/dev/uhid`；`UhidKeyboard` 已经这样建了 "XDesk Web Keyboard"。不要 root。
- 写进去的 HID 描述符由内核的 hid-generic 解析成 evdev 设备，Android 的 EventHub 当外接设备接进来。
- `injectInputEvent` 注入手柄事件不行：注入的事件没有对应的 `InputDevice`，游戏枚举不到手柄；GeForce NOW 对注入的鼠标事件也不认（GAMING.md §2）。

## 3. 焦点

手柄事件和键盘一样，发给**顶层焦点显示器**上有焦点的窗口。手柄就在手机屏上，所以关键是碰手机屏不能把焦点拉回手机。

- 这已经有了：外屏上跑着应用时，手机上的 XDesk 窗口是 `FLAG_NOT_FOCUSABLE` 的（`XDeskActivity.updatePhoneFocus()`，`phonePassive`），每次手指按下还会 `refocusExternal()`。AOSP 14–16 的 `WindowState.receiveFocusFromTapOutside()` 经 `canReceiveKeys` 判断，不可聚焦的窗口被点，不会把它的显示器挪到顶层（两份审计都核对了源码）；触摸板一直靠这个工作。
- 所以手柄做成 **XDeskActivity 里的一个全屏层**（和全屏触摸板 `padFullscreen` 同一种做法），不另开 overlay 窗口：焦点、熄屏变暗（`bumpIdle`）、返回键都沿用 Activity 现有的处理。
- 手柄打开时，手指按下**不**调 `apps.phoneTouched()`：它会置 `focusStale`、退出游戏直通，而手柄和游戏直通（实体键鼠 + 虚拟手柄）可以同时用。`refocusExternal()` 照旧。
- 手柄打开时，如果 `phonePassive` 是 false（比如启动器的搜索框要了焦点），先关搜索的输入状态，让窗口回到不可聚焦。手柄层盖住了启动器，本来也不会同时打开。
- 测试版验收：外屏开游戏，在手机手柄上连续按，`dumpsys window` 的 `mTopFocusedDisplayId` 一直是外屏。

## 4. 设备：身份和描述符

**冒充 Xbox 360 有线手柄**：USB 总线，VID/PID 045e:028e，和 scrcpy 3.1 一样。scrcpy 3.0 用的是通用 VID，因为游戏不认（scrcpy #5362）才改成这个。在内核这边，028e 不是 HID 设备的产品号（真手柄走 xpad 驱动），所以 uhid 设备绑的是 hid-generic，不用回应 feature 报告；在 Android 这边，按 VID/PID 选中 `Vendor_045e_Product_028e.kl`，游戏、SDL、Chrome 的映射表都认识它。

一个 Gamepad 应用集合，没有 report ID，一份报告 13 字节（P0 实测过的就是这个，§8）：

| 字节 | 内容 | evdev | Android |
|---|---|---|---|
| 0–1 | Button 页 1–15，各 1 位，补 1 位 | BTN_SOUTH… | 见下 |
| 2 | hat，4 位，0–7 = 上起顺时针 8 个方向，8 = 空（Null State）；补 4 位 | ABS_HAT0X/Y | AXIS_HAT_X/Y |
| 3–4 | 左摇杆 X，16 位有符号 | ABS_X | AXIS_X |
| 5–6 | 左摇杆 Y | ABS_Y | AXIS_Y |
| 7–8 | 右摇杆 X（Usage Rx） | ABS_RX | AXIS_Z |
| 9–10 | 右摇杆 Y（Usage Ry） | ABS_RY | AXIS_RZ |
| 11 | 左扳机（Usage Z），0–255 | ABS_Z | AXIS_LTRIGGER + AXIS_BRAKE |
| 12 | 右扳机（Usage Rz），0–255 | ABS_RZ | AXIS_RTRIGGER + AXIS_GAS |

按钮：hid-input 把 Gamepad 集合里的 Button 1–15 依次映射成 BTN_SOUTH、EAST、C、NORTH、WEST、Z、TL、TR、TL2、TR2、SELECT、START、MODE、THUMBL、THUMBR。所以：

| 键 | 按钮号 | Android |
|---|---|---|
| A | 1 | BUTTON_A |
| B | 2 | BUTTON_B |
| X | 4 | BUTTON_X |
| Y | 5 | BUTTON_Y |
| LB | 7 | BUTTON_L1 |
| RB | 8 | BUTTON_R1 |
| Back | 11 | BUTTON_SELECT |
| Start | 12 | BUTTON_START |
| Home（Guide） | 13 | BUTTON_MODE |
| L3 | 14 | BUTTON_THUMBL |
| R3 | 15 | BUTTON_THUMBR |

3（C）、6（Z）不用；9、10（L2/R2 按键）在扳机按下时也一起按下（有的游戏只认按键）。

其他：
- 名字 "XDesk Gamepad"，`uniq` 固定为 "xdesk-gamepad"：每次都是同一个手柄，游戏里改过的键位能留住。
- 现有的设备判断都自然跳过它，不用改：`EvdevReader` 只接有鼠标能力或 KEY_A/M/Q/Z/SPACE 的设备（`xdesk_evdev.c` 的 `capabilities`），手柄得 0；`hasExternalInput()`、`InputInjector.physicalKeyboard()`、`ImePlacement.hasPhysicalKeyboard()` 只认鼠标或字母键盘，它是 `KeyboardType: 1`（非字母）。真被当成键盘，说明描述符错了，要改描述符而不是按名字跳过。
- 不做振动：hid-generic 不建 force feedback。要振动就得冒充一个 hid-microsoft 支持振动的蓝牙手柄并处理 `UHID_OUTPUT`，以后再说。
- 不读 `/dev/uhid`：hid-generic 不发 GET_REPORT，只写就够（同 UhidKeyboard）。

## 5. 设备的生命周期

- **建**：在手机上打开手柄时，App 调 `gamepadOpen()`，注入服务在手柄线程上建设备、马上写一份中立报告（全松开、摇杆 0、hat 8、扳机 0）。不像 UhidKeyboard 那样等设备出现（游戏自己处理热插拔）。
- **关掉手柄界面**：调 `gamepadEnd()`，写中立报告，**不拆**设备；否则游戏会弹「手柄已断开」并暂停。
- **拆**：会话结束（`InjectorService` 会话收尾，和 `closeWebKeyboard()` 同处）、`shutdown()`、`destroy()`、宿主（XDesk 的 binder）死亡时：中立报告 + `UHID_DESTROY` + 关 fd。Shizuku 宿主进程退出时 fd 关闭，内核自动拆；ShellServer 宿主常驻，所以必须显式拆。会话结束后再来的调用不理。
- **手柄线程**：建、写、拆都在 `GamepadHid` 自己的单线程 executor 上按顺序做，不走 InjectorService 的 worker（worker 会在键之间 sleep）。同一个 binder 上的 oneway 调用按发出的顺序送达，每份报告都是完整状态，`gamepadEnd` 和报告走同一条队列，所以不会被旧报告覆盖。写失败（设备没了）记日志、丢掉对象，下次 `gamepadOpen` 或报告时重建。
- App 端在这些时候调 `gamepadEnd()`：手柄层关闭、`ACTION_CANCEL`、进编辑模式、外屏目标变了、Activity `onPause`。注入服务断开重连后不用特别处理：新的注入服务收到第一份报告时就建设备。
- 已经连着实体手柄时，两个都能用。玩家编号由 Android 和游戏决定，不保证。

## 6. 手机端界面

### 6.1 进出

- 运行界面顶上那排按钮（声音、触控模式、键鼠、启动器、设置）加一个「手柄」按钮，只在外屏目标是安卓应用（`apps.isActive()`）时显示。
- 打开：手柄层盖住整个运行界面；隐藏系统栏（沉浸式，同 `padFullscreen` 的 `applyImmersive`）；屏幕方向改成 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，关闭时恢复成设置里的方向（`ORIENTATIONS`）。
- 手柄上有一个小的「⋯」菜单按钮（默认顶部正中，半透明，编辑时可以拖走）。菜单里：退出手柄、编辑布局、选布局、透明度、按键振动开关。菜单画在手柄层里，不开对话框（对话框是可聚焦的窗口，会抢焦点）。
- 返回手势或返回键：先关菜单或退出编辑；都没有就退出手柄（`onBackPressed` 最前面处理），不会结束 XDesk。
- 外屏目标不再是安卓应用（换成 X 桌面、Mac，或会话结束）：手柄自动关掉。
- 系统的返回手势：手柄层整块设 `setSystemGestureExclusionRects`（系统每边只认最下面 200 dp，手势条排除不了，同全屏触摸板）。

### 6.2 控件和手感

- 控件：左摇杆、右摇杆、方向键、A B X Y、LB RB、LT RT、Back、Start、Home、L3、R3、菜单。
- 多点触控：每个手指按下时绑定它碰到的控件，直到抬起（nx2android 的做法）。方向键在手指滑动时换方向；ABXY 滑到另一个按钮上就换成那个（同一根手指同时只按一个）。
- 判定：按下点到控件的圆角矩形求距离（SDF），取最近的控件；按钮外沿多算四分之一尺寸的容错（至少屏高的 4.5%），摇杆和方向键外沿多算一半。
- 摇杆：浮动中心（按下的位置就是中心，偏移到摇杆半径为满），可在菜单里改成固定中心。死区 10%，圆形归一化（斜推不超过 1）。
- 方向键：8 方向，角度划成 8 份，中心 25% 半径内为空。
- 扳机：按下即满（255），同时按下 L2/R2 键。
- L3、R3：单独的按钮（默认在摇杆旁边上方）。
- 按下时手机短振一下（`HapticFeedbackConstants.VIRTUAL_KEY`），菜单里可关。
- 按下的控件高亮。

### 6.3 布局模型（借 nx2android）

- 控件中心：`x` = 锚点（0 左边、0.5 中间、1 右边）× 屏宽 + 偏移（单位是屏高）；`y` = 屏高的比例。宽、高也以屏高为单位，圆形控件保持圆形。这样拇指离边缘的距离在任何宽高比的手机上都一样。
- 整体放大系数：让 ABXY 至少 10 mm 宽（按 `DisplayMetrics.ydpi` 和屏高算），位置不变。
- 自己拖过的控件：中心存成屏宽、屏高的比例；每个控件有一个大小系数（0.5–2）和是否隐藏。没动过的跟着默认布局走。
- 一套布局 = 名字 + 每个控件的（是否拖过、x、y、大小系数、是否隐藏）+ 透明度（0.2–0.8，默认 0.5）+ 摇杆模式（浮动或固定）。

### 6.4 编辑布局

- 菜单里「编辑布局」：报告停发（先 `gamepadEnd()`），控件显示虚线框。
- 拖动控件，拖时贴屏高 2% 的网格，不出屏。
- 选中的控件旁边出一条工具条：缩小、放大（每次 ×0.9 / ×1.1，0.5–2）、隐藏、恢复默认。
- 被隐藏的控件在编辑时显示成很淡的虚线，点一下恢复显示。
- 顶部一条：完成、取消、全部恢复默认、透明度 −/+。

### 6.5 多套布局和按应用切换

- 内置两套：「标准」（全部控件）和「简洁」（左摇杆、方向键、ABXY、LB/RB、Start，适合横版游戏）。内置的也可以改，改过的存下来；「全部恢复默认」回到内置的样子。
- 菜单里「新建布局」（复制当前的，自动命名「布局 N」）、「删除布局」（内置的不能删）。
- 存法：`SharedPreferences`（`gamepad`）里一个 JSON 数组，每项是一套布局，带版本号。
- 按包名记住用的布局（`layout_for_<包名>`）；外屏换应用或手柄打开时自动选它，没记过的用「标准」。
- 导入导出：菜单里「复制布局」把当前布局的 JSON 放进剪贴板，「粘贴布局」读剪贴板里的 JSON 新建一套（校验版本和字段，坏的提示不能用）。

## 7. 代码结构

shell 端（注入服务）：
- `GamepadHid.java`：常量（名字、VID/PID、描述符）、`open()`、`report(...)`、`neutral()`、`close()`，全部在自己的单线程 executor 上。照 `UhidKeyboard` 的 `UHID_CREATE2` / `UHID_INPUT2` / `UHID_DESTROY` 写法；报告的 buffer 复用。
- `IInjector.aidl` 加（编号接在现有最大的 46 后面）：
  - `oneway void gamepadOpen() = 47;`
  - `oneway void gamepad(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) = 48;`：一整份状态。buttons 按 §4 的按钮号，第 n 号在第 n−1 位。
  - `oneway void gamepadEnd() = 49;`
- `InjectorService`：持有一个 `GamepadHid`；会话收尾、`shutdown()`、binder 死亡时 `close()`。

App 端：
- `GamepadLayout.java`：控件表（种类、默认锚点和位置、大小、按钮号、标签）、一套布局的数据和 JSON 读写、控件在屏上的位置和大小、SDF 判定、方向键角度到方向、摇杆归一化。纯 Java，不依赖 View。
- `GamepadView.java`：画控件（Canvas：半透明的圆和圆角矩形加文字）、多点触控 → 状态 → `AppSession.gamepad(...)`（状态变了才发）、菜单、编辑模式、振动。
- `AppSession`：`gamepadOpen()`、`gamepad(...)`、`gamepadEnd()`，转给 IInjector（和 `focusExternal()` 同样写法）。
- `XDeskActivity`：「手柄」按钮；手柄层的显示和关闭；方向、沉浸式；`dispatchTouchEvent` 在手柄打开时不调 `phoneTouched()`；`onBackPressed`；`updateStatus` 里目标不是安卓应用就关；`onPause` 时 `gamepadEnd()`；注入服务重连时再 `gamepadOpen()`。
- 字符串（中英）、图标 `xd_ic_gamepad.xml`。

文档：INPUT.md §15（手柄一条改成指向本文）、README（中英）、SHORTCUTS 不变、release-notes。

## 8. P0 实验结果（2026-11-18，15 Ultra，HyperOS 3）

探针 `uhidpad.c`：Termux 里 clang 编，`rsh` 以 shell 身份跑，从标准输入读按键和轴。描述符两种：a 是通用身份（1209:7d10，右摇杆 Z/Rz，扳机 Brake/Accelerator），x 是 Xbox 360 身份（045e:028e，右摇杆 Rx/Ry，扳机 Z/Rz）。

- 能建：shell 能写 `/dev/uhid` 建手柄。两种都是 `Classes: KEYBOARD | GAMEPAD | JOYSTICK | EXTERNAL`、`Sources: KEYBOARD | GAMEPAD | JOYSTICK`、`KeyboardType: 1`、`ControllerNum: 1`。
- 键：按钮 1 → BTN_GAMEPAD(0x130)，2 → BTN_EAST(0x131)，和 hid-input 的映射一致。
- 轴：两种在 Android 上得到的完全一样：X、Y、Z、RZ、HAT_X、HAT_Y，扳机同时报 LTRIGGER 和 BRAKE、RTRIGGER 和 GAS。x 用的键位文件是 `Vendor_045e_Product_028e.kl`，a 用 `Generic.kl`。
- 振动：两种都没有振动器。
- 延迟：写入时刻和 `getevent -t` 的内核时间戳在同一毫秒内。
- 选 x（§4）：Android 上一样，游戏按 VID/PID 认手柄时更好。

## 9. 分阶段

一次做完 §4–§7（用户定：方案定了一次做好）。实现顺序：设备和 AIDL → 固定的标准布局能玩 → 编辑器 → 多套布局、按应用切换、导入导出。一个测试版。

以后：振动、陀螺仪当右摇杆、连发；接收端（平板、网页的 Gamepad API）的手柄转发进手机的 uhid，做本地 2 号玩家。

## 10. 验收

- 外屏上开 Chrome 的手柄测试页（Gamepad API），每个键、每个轴都对，摇杆斜推不超过 1，松手回中。
- 一个本地安卓游戏和 GeForce NOW 串流中都能玩；在手柄上连续按，焦点不回手机（`mTopFocusedDisplayId` 是外屏）。
- 有线外屏和无线接收端两种情况。
- 关手柄界面不卡键；游戏不报手柄断开；会话结束设备消失（`dumpsys input` 里没有了）；ShellServer 宿主下再开一次会话不会出现两个手柄。
- 和游戏直通（Ctrl+Alt+G）同时用：实体键鼠和虚拟手柄都进游戏，碰手柄不退出直通。
- 「键鼠」按钮不因虚拟手柄出现。
- 布局：拖动、缩放、隐藏、新建、删除、换应用自动切换、复制再粘贴一致；在 10T（不同宽高比）上位置合理。
- 返回手势退出手柄，不退出 XDesk；退出后屏幕方向恢复。
