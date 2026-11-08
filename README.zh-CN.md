| 小米手机上安卓应用收不到输入 | 在开发者选项里打开 **USB 调试（安全设置）** |
| 车机上用 GPU 的程序出现闪烁的色块 | Turnip 的分块渲染被 Android Auto 显示器的合成打断。用 `TU_DEBUG=sysmem` 启动它们；Chromium 再加 `--use-angle=gl --disable-features=Vulkan` 还更快 |
# XDesk

**不用 root，从安卓手机把应用放到大屏幕上。** XDesk 可以把安卓应用全屏放到外接显示器上，也可以运行 Termux、proot 或 chroot 里的 Linux 桌面。手机屏是控制台：启动器和切换器、触摸板、键盘和设置。安卓应用要用无线调试激活一次，已经在用 Shizuku 的话也可以用它。Linux 桌面都不需要。

XDesk 的生命周期和桌面无关。打开或划掉 XDesk 都不会启动或结束 XFCE。Termux 桌面只是启动器里的一项，由你自己启动和结束。

[English](README.md) · 基于 [Termux:X11](https://github.com/termux/termux-x11)（[上游说明](README.upstream.md)）· GPL-3.0

> **现状：** 这是早期的个人项目，每天在一台手机上使用：欧版（EEA）小米 15 Ultra（型号 25010PN30G，骁龙 8 Elite，HyperOS 3，Android 16，BL 未解锁）。别的机型都没有试过，包括这款手机的国行版，其他机型和 ROM 可能表现不同。App 界面跟随系统语言：简体中文时显示中文，其他语言显示英文。安卓 13 及以上也可以在 XDesk 的设置里自己选。非常欢迎反馈其他机型的结果。

## 安装

在 [Termux](https://github.com/termux/termux-app) 里运行：

```sh
curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
```

它会安装 `xdesk` 命令和启动器，查找你已有的桌面，并下载 App。接着：

1. 安装它下载的 App。如果文件管理器提示“未安装应用”，就用 `adb install -r xdesk.apk`。小米手机要先在开发者选项里打开 **USB 安装**。
2. 插上显示器，打开 **XDesk**。
3. 选择要运行什么：Termux 环境或安卓应用。不选就不会启动任何东西。

还没有桌面？比如：`pkg install xfce4 xfce4-terminal && xdesk setup`。

> 把脚本交给 shell 之前，请先看一眼：[install.sh](xdesk/install.sh)。从 v0.2.0 起 APK 用 XDesk 自己的发布密钥签名。v0.1.x 用的是上游公开的测试密钥，安卓不允许直接覆盖更新，请先卸载旧的 XDesk。

## 需要什么

- 支持 **USB-C 视频输出**（DisplayPort Alt Mode）的安卓手机。插上显示器能镜像就符合
- 或者不用线：同一网络里另一台装了 XDesk 接收器的安卓设备（见「无线显示」）
- USB-C 转 HDMI 线或扩展坞
- Android 11 或更高（只在 Android 16 上实测过）
- Termux

## 配合任意环境使用

XDesk 只负责显示。任何能连上它的 X socket 的程序都能显示出来。

```sh
xdesk x                 # 只启动 X 服务器和声音
xdesk run 命令...        # 在显示器上运行任意程序
xdesk setup             # 查找 Termux 和 proot-distro 里的桌面
xdesk list              # 列出环境，* 是默认
xdesk use 名字           # 选默认环境
xdesk start [名字]       # 启动环境
xdesk switch 名字        # 结束当前环境并启动另一个，窗口不断
xdesk add 名字 '命令'    # 添加自己的环境
xdesk rm 名字            # 删除环境
xdesk status            # 查看运行状态
xdesk stop              # 全部结束
```

一个环境就是一条命令，存放在 `~/.config/xdesk/sessions/名字.sh`。这些文件可以随便改，`xdesk setup` 不会覆盖。

| 环境 | 怎么连接 |
|---|---|
| Termux | `xdesk setup` 会找到，或者 `xdesk run xterm` |
| proot-distro | `xdesk setup` 会找到。手动：`xdesk add box "proot-distro login ubuntu --shared-tmp -- env DISPLAY=:1 PULSE_SERVER=127.0.0.1 dbus-launch --exit-with-session startxfce4"` |
| 手动 proot | 把 `$PREFIX/tmp` 绑定到 `/tmp`，设 `DISPLAY=:1` |
| chroot（要 root） | 把 `$PREFIX/tmp/.X11-unix` 绑定到 chroot 里的 `/tmp/.X11-unix`，设 `DISPLAY=:1`。目前没有测试过 |

GPU 加速只为 Adreno 上的 Termux 配置了（zink + Turnip）。proot 和 chroot 里默认是软件渲染，除非你自己配置 Mesa。调试够用，玩游戏不行。

proot 和 chroot 的声音通过 `127.0.0.1` 的 PulseAudio 传给 App。

### 从 App 启动

启动器会列出你的环境，点哪个就启动哪个，这需要在 Termux 里运行命令的权限。没有权限时，启动器里会有**接上 X 显示**，再自己运行 `xdesk start`。要允许的话：

```sh
xdesk enable-app-start
```

这会在 Termux 里设置 `allow-external-apps = true`，允许 App 请求运行命令。每个 App 还要单独获得权限，XDesk 会在你点环境时询问。

正在运行的环境会在启动器上标出来。点它只是接回，不会重启任何东西。要结束它，用那一行的**结束**按钮或设置里的同名项，因为离开 XDesk 不会结束它。也可以运行 `xdesk stop`。

## 外接屏上的安卓应用

在选择界面点一个应用，它就全屏运行在外接屏上，手机做它的触摸板和键盘。实体鼠标和键盘先留给手机，按顶部的**接管**或 Ctrl+Alt+M 才转给外接屏，按**交还**或再按一次 Ctrl+Alt+M 交还。接着实体键盘时，接管也会把输入法的选词框移到外接屏、显示在输入框旁边，交还后回到手机。**启动器**把启动器盖在会话上，外接屏不变。**回到触摸板**关掉它。

- **激活，不用装别的 App。** 安卓不允许普通 App 给别的 App 注入输入，所以这个 APK 的一个小服务以 shell 用户运行，由它读取 `/dev/input` 并注入事件。XDesk 通过无线调试（Android 11 起才有）自己启动它。你只要打开无线调试，输入一次配对码，在通知里或 XDesk 的激活页里都行。以后打开 XDesk 时，只要无线调试开着，它会自己启动服务。Termux 环境不需要这些。
  - 激活时要连着 Wi-Fi：没有 Wi-Fi 时安卓会把无线调试关掉，手机自己的热点可能不算。服务运行之后 Wi-Fi 断了也没关系。
  - 重启手机后要重新激活。如果服务成功给了 XDesk `WRITE_SECURE_SETTINGS` 权限，打开 XDesk 时只要连着 Wi-Fi，它会自己打开无线调试，服务启动后再关掉，否则会告诉你怎么做。有的系统不给这个权限。
  - 运行期间不要关 USB 调试或开发者选项。
  - 结束 XDesk 后服务还在运行，但不占着键鼠、声音路由和显示设置，也只有 XDesk 自己的 uid 能调用它。重启手机、更新或卸载 XDesk，或在设置里点**停止内置服务**后它就没了。
  - XDesk 的密钥一直保持配对，直到你在开发者选项里撤销。无线调试开着时，这把密钥可以打开手机上的 shell，和任何已配对的设备一样。
- **[Shizuku](https://shizuku.rikka.app/) 还能用。** 如果它在运行并且允许了 XDesk，XDesk 就用它，服务和以前一样通过它运行。
- **需要悬浮窗权限，** 因为安卓自己的光标留在手机屏上，XDesk 要在外接屏上画一个。
- **Dock 和切换。** 选择界面可以按名字或包名搜索。点一行右边的星加入 Dock。长按 Dock 或列表里的图标，会打开这个应用的卡片：Dock 位置、界面大小、鼠标或手指、关闭应用。Dock 一直留在触摸板界面上，点一下就切换外接屏上的内容，不用回选择界面。已经在运行的应用只是回到前台，状态还在。只要安卓没有把它们关掉，桌面可以在应用后面继续运行，应用的任务也可以留在桌面后面。应用在前面时桌面没有声音。
- **RustDesk 里的光标。** 远程会话中，XDesk 的光标在远程画面上是一个淡淡的小圆点，不妨碍远程光标，在 RustDesk 自己的界面上也看得到位置。在工具栏上和点工具栏后的几秒内是正常光标。判断会话靠的是在一个版本上看到的窗口标志。判断错了就打开“始终显示 XDesk 光标”。
- **安卓的返回键。** **返回 App** 键把 Back 发给外接屏上的应用，给那些靠边缘滑动返回的应用用。鼠标侧键发送 Back 和 Forward。RustDesk 会话中，侧键仍然交给 RustDesk。Back 之后外接屏上没有应用的话，会打开列表；露出后面的另一个应用时，输入方式和界面大小跟着它。
- **两种操作方式（每个应用单独设），光标显示是哪种。** 键鼠用 XDesk 的黑色箭头，是键盘和鼠标：手机的触摸板、按钮和按键，还有实体键鼠。触控给只听触摸的应用或网页，用圆润的黑色箭头，不动时自动隐藏，眼睛一直看大屏幕：一根手指移动光标，轻点就是光标处点按，轻点后按住滑动是拖动，按住不动是长按。两根以上（最多五根）是真正的多指，围绕光标摆放，外接屏上每根手指有一个点，捏合和滑动都能直接用。这时实体鼠标当手指用，左键一根，右键两根，滚轮是滑动。XDesk 记住每个应用的方式，默认键鼠，RustDesk 固定键鼠。
- **触控区全屏。** 触控区角上的图标让它铺满整个手机屏，系统栏隐藏，XDesk 会记住。角上半透明的小图标可以返回 App、打开键盘和退出全屏，手机的返回也能退出。键鼠模式下左右键不显示，轻点、双指轻点和长按拖动能代替。
- **键盘。** 应用弹出的软键盘在手机上。只有接着实体键盘并且接管时，才会改外接屏的输入法设置；交还、会话结束或注入服务退出时恢复原样。
- **仪表盘。** XDesk 开着又接了外接屏时，外接屏最底下显示时钟和状态，应用退出后露出来的就是它。结束 XDesk 后它消失，外接屏回到镜像。
- **结束会话。** 外接屏一直保持原样，直到你用**结束 XDesk**（在启动器或设置里）结束，或从最近任务划掉 XDesk。这时外接屏上的应用移回手机（不关闭），外接屏回到镜像，键鼠释放，声音路由和界面大小恢复。Termux 桌面继续运行，只是没有窗口，启动器上显示为运行中。移回应用需要注入服务，它不在时应用留在外接屏上，XDesk 会提示。回到启动器或手机桌面都不会结束会话：接管的键鼠还在，Ctrl+Alt+M 仍然有效。
- **XDesk 不会自己重启桌面。** X 服务器停了，启动器会说明，点一下环境就能重新启动。
- **外接屏界面大小。** 外接屏上有安卓应用时，可以在设置里填 120 到 220 dpi 作为默认值，按物理显示器记住。应用可以在卡片里有自己的值，启动前和每次切到它时生效。值不同才会改，因为应用要重新布局。光标大小不跟着变，它按显示器高度算。会话结束时恢复，镜像回到正常。Termux 桌面显示时，外接屏用另一个值，默认 168 dpi，也在这里设置。它只影响外接屏上的安卓窗口，比如输入法的选词框；桌面本身用自己的 DPI 设置。HyperOS 清除密度后会退回手机屏的值，所以 XDesk 改为直接设成外接屏自己的值。需要注入服务。
- **只在一台手机上测试过，** 也只试过 RustDesk。
- **RustDesk 的输入方式依赖它的内部实现。** 普通鼠标事件在那里会被 Flutter 弄坏拖动，所以 XDesk 把按键变化作为悬停事件发送，并靠猜测判断一次点击是不是给 RustDesk 自己的工具栏。RustDesk 更新后可能失效。需要特殊方式的应用列在 `AppInputProfile.java`，其他应用用普通的安卓鼠标事件。
- **手机键盘只能发送键盘打得出来的字符。** 中文等输入法文字到不了安卓应用。

## 无线显示

没有线？在同一网络里的另一台设备上装 **XDesk 接收器**（[Releases](https://github.com/n2ray/xdesk/releases) 里的 `xdesk-receiver.apk`），旧手机、投影机、电视盒子都行，要 Android 5.0 以上。XDesk 就通过 Wi-Fi 把一块显示器传过去，用起来和接线一样：安卓应用、Termux 桌面和声音都可以。接收器只负责显示画面、播放声音，所有输入都留在手机上。

1. 在另一台设备上打开 XDesk 接收器，它会显示自己的名字和地址，然后等待连接。
2. 在 XDesk 里打开设置 → **外屏** → **无线显示**，选择这台接收器。接收器靠 mDNS 发现；不在同一网段时，用**输入地址**，填接收器上显示的地址。
3. 第一次连接时，输入接收器上显示的 4 位配对码。配对过的设备以后直接连，不用再输。

- **需要注入服务**（内置服务或 Shizuku）：要传的那块显示器由它创建。无线显示在服务里有自己的会话，应用开关不会影响它；服务把显示器弄丢了，XDesk 会重新建一块。
- **画面**是 H.264，分辨率跟随接收器屏幕，长边最多 1920，并且不超过它的解码器能力，1080p 约 12 Mbit/s，低延迟解码。**声音**是手机的全部输出，抓成 PCM 在接收器上播放，期间手机静音。想留在手机上听（比如戴耳机），在声音输出里选手机上的输出；选**接收端**就再传过去。投屏中随时可以切换，下次连接沿用。
- **停止**：在同一个设置项里、**结束 XDesk**，或者关掉接收器都行。
- 配对令牌不加密传输：它防止同一网络里的其他手机误投，防不了能监听网络的人。协议见 [receiver/PROTOCOL.md](receiver/PROTOCOL.md)。

## 网页显示

任何有浏览器的设备都能当外接屏：iPad、笔记本、智能电视，什么都不用装。在设置 →**显示** → **网页显示**里打开，XDesk 会在端口 47150 提供网页，并按网络列出要打开的地址：

- **无线：**手机的 Wi-Fi 地址；对方连手机热点时，是热点的地址。
- **有线：**插上数据线，打开 **USB 网络共享**（这一行会跳到网络共享设置），就会多一个有线地址。延迟比 Wi-Fi 低，也更稳。iPad 和 Mac 只认 NCM：先点 **USB 网络共享用 NCM**（需要内置服务给的权限，否则运行 `adb shell settings put global tether_force_usb_functions 1`），再把网络共享关掉重开。

浏览器第一次连接时，要输入 XDesk 在手机上显示的 4 位码，之后就记住这台手机了。浏览器里有画面、手机的声音，鼠标、键盘和触摸都会传回手机：

- **鼠标：**第一次点击锁定鼠标（Pointer Lock），按 Esc 解锁。按键和滚轮都传给外屏。
- **触摸**和手机上的触摸板一样：单指移动，轻点是单击，点一下再按住拖动是拖拽，双指滚动，双指轻点是右键，三指轻点是中键。
- **键盘：**按键按键码发送，快捷键都能用；Mac 和 iPad 键盘上 Command 当作 Ctrl。输入法打出的文字（中文、点 ⌨ 弹出的 iPad 软键盘）按文字发送。
- **防误操作：**顶部的工具栏（鼠标移到顶边，或者点屏幕顶边）有全屏、键盘、声音和断开。浏览器的后退被拦住，离开前会先确认；页面不会缩放、滚动、选中文字或弹出菜单。Chrome 全屏时，Esc 和浏览器快捷键也交给外屏。iPad 上**添加到主屏幕**后，可以像 App 一样全屏打开。

和无线显示是同一套，所以：同一时间只能有一个浏览器或接收端，需要注入服务，手机上要开着 XDesk。用的是普通 HTTP 加配对令牌，只适合可信的网络。视频是 H.264：浏览器允许时用 WebCodecs 解码（localhost、HTTPS），否则用 Media Source Extensions，普通 HTTP 下 Safari、Chrome、Edge、Firefox 都能用。网页和协议见 `lorie-app/src/main/assets/web/` 和 [receiver/PROTOCOL.md](receiver/PROTOCOL.md)。

## 功能

- 手机当触摸板：移动、轻点、双指右键和滚动，长按或轻点后按住拖动
- 软键盘，支持输入法打字（包括中文），还有快捷键栏（Esc、Tab、Ctrl、Alt、Super、Shift、方向键）
- 实体鼠标和键盘，转发到桌面。顶部的**接管**把键盘和鼠标直接交给外接屏上的桌面窗口，实体键盘的输入法选词框就显示在外接屏上；**交还**回到手机。接管需要注入服务
- 安卓应用全屏放到外接屏上（用无线调试激活，或者用 Shizuku）
- 无线显示：通过 Wi-Fi 把以上内容连同声音放到投影机、电视盒子或旧手机上的 XDesk 接收器
- 声音输出可切换：手机扬声器、HDMI、蓝牙、USB、有线耳机、系统默认。所有模式共用一个设置，切换后依然有效。注入服务运行时，它设置系统的首选媒体设备，桌面、安卓应用和其他声音都生效，回到选择界面或退出时清除，下次会话再按你的选择设置。没有注入服务时只能尽力指定桌面声音：在测试手机上，接着 HDMI 时没法把声音拉回手机扬声器。这只是把输出指向那个设备，音质和蓝牙编码没法保证。如果 XDesk 被强杀到来不及清除，在 XDesk 里选一次系统默认，或者重启手机
- 分辨率档位、灵敏度、触控界面横竖屏、闲置时调暗手机屏
- 拔掉显示器、切换 App、锁屏后都能恢复
- Android Auto，仅停车时（实验性）：XDesk 出现在车机的应用里，车机屏上显示时钟。可以在手机上把 Linux 桌面放到车机上，分辨率跟随车机屏，手机做触摸板和键盘，声音从车里播放。安卓应用（YouTube、Chrome 等）也能上车机，跑在 XDesk 为它们建的显示器上，可以直接点车机屏。同时接着外接屏时用外接屏

可以和官方 Termux:X11 共存，互不影响。但不要同时运行，因为都用 `:1`。

## 工作原理

```
 手机屏                              外接屏
┌───────────────────────┐           ┌───────────────────────┐
│ XDeskActivity         │  直接     │ MainActivity（上游）   │
│ 触摸板、按键、声音    ├──调用────►│ LorieView 显示 X       │
└───────────┬───────────┘           └───────────▲───────────┘
            │ 本机端口（声音）                  │ X11 socket
┌───────────▼───────────────────────────────────┴───────────┐
│ xdesk：X 服务器、PulseAudio                                │
│ Termux、proot、chroot：你的桌面或程序                      │
└───────────────────────────────────────────────────────────┘
```

- **一个 App，两个窗口。** 上游的 `MainActivity` 原样放在外接屏上。`XDeskActivity` 在手机屏上，和它在同一个进程，直接调用 `LorieView.sendMouseEvent`、`sendKeyEvent`、`sendTextEvent`。
- **X 服务器在 Termux 里运行**，用的是为这个包名构建的启动器。
- **安卓应用** 由注入服务（`InjectorService`）放到外接屏上，它以 shell 用户运行，用 `injectInputEvent` 发送事件。光标由 `CursorOverlay` 绘制。
- **声音** 从 PulseAudio 的 null sink 走 47130 端口，由 App 用 `AudioTrack` 播放，所以不需要特殊权限就能选输出设备。这个端口只绑定本机，但没有验证，手机上的其他 App 理论上能读到桌面的声音。
- **无线显示：** 注入服务创建一块公开显示器，它的 Surface 就是 MediaCodec H.264 编码器的输入，再用 `REMOTE_SUBMIX` 录下手机的声音输出（`WirelessDisplay`、`OutputCapture`）。XDesk 连接接收器的 47140 端口，把这块显示器当作外接屏。

改动几乎都在 `lorie-app/` 和 `xdesk/`，合并上游比较简单。

## 常见问题

日志在 `$PREFIX/tmp/xdesk.log`。

| 现象 | 可能的原因 |
|---|---|
| 一直等待 | 你接上了 X 显示，还没有程序在运行。运行 `xdesk start`，再看 `xdesk status` |
| 选择界面没有环境 | 运行 `xdesk enable-app-start` 和 `xdesk setup`，并允许 XDesk 运行 Termux 命令 |
| 安卓应用列表不全 | 有些 ROM 限制 App 能看到什么。小米手机检查“获取应用列表”权限 |
| 注入服务启动不了 | 确认连着 Wi-Fi、无线调试开着，再回到 XDesk。重启手机后要重新激活。用 Shizuku 的话，确认它在运行 |
| 配对失败，或者通知里输入不了 | 配对框要一直开着。在 XDesk 的激活页里输入配对码，离开设置配对框就关的话，用分屏或小窗 |
| 提示 “Display :1 is used by another X server” | 官方 Termux:X11 在运行，先结束它，比如 `pkill -f termux-x11` |
| App 的启动按钮失败 | 运行 `xdesk enable-app-start`，再检查权限 |
| 桌面没有铺满 | 它在 App 连上之前就启动了。先打开 App，再重启环境 |
| 外接屏闲置几分钟后黑屏 | XFCE 的屏保。动一下鼠标，或者调大 xfconf `xfce4-screensaver` 的 `/saver/idle-activation/delay` |
| 手机屏上出现安卓光标 | 鼠标抓取丢了，点一下 XDesk 的手机界面 |
| 没有声音 | 看顶部的声音输出，很多显示器没有喇叭 |
| 软键盘上 Ctrl + 字母没反应 | 拼音模式下字母留在输入法里，切到英文布局或用实体键盘 |

## 局限

- 安卓应用要用无线调试或 Shizuku 激活，外接屏同一时间只能放一个全屏应用或桌面，切换要经过选择界面
- Android Auto 只能停车时用。Android Auto 关掉 XDesk 或断开后，桌面继续运行，只是没有窗口；安卓应用也继续运行，在车机上重新打开 XDesk 后回来。有 DRM 的视频（部分流媒体应用）在车机上可能是黑屏。车机上的安卓应用和外接屏一样要先激活，会话结束时回到手机
- Termux 和 XDesk 都要保持运行，系统清理可能杀掉它们
- 无线显示：一台接收器同时只接一台手机，只传画面和声音，流畅程度取决于 Wi-Fi。在一台 32 位 Android 9 投影机上测试过
- 只在一台手机（欧版小米 15 Ultra）上测试过

## 构建

默认分支是 `xdesk`，`master` 跟随上游。推送后由 GitHub Actions 构建，`v0.2.0` 这样的标签会发布 Release，用仓库 secrets 里的密钥签名（见 `.github/workflows/release.yml`）。带后缀的标签（如 `v0.3.0-rc1`）发布为预发布版本。

```sh
git clone --recursive https://github.com/n2ray/xdesk
cd xdesk && ./gradlew assembleDebug      # 需要 Android SDK 和 NDK
```

合并上游：

```sh
git remote add upstream https://github.com/termux/termux-x11
git fetch upstream && git merge upstream/master
```

XDesk 依赖上游的 manifest、`com.termux.x11` 下的类名和 `LorieView` 接口。包名在 `lorie-app/build.gradle` 和 `shell-loader/build.gradle` 里设置，两边要一致。

## 许可证

GPL-3.0，和 Termux:X11 相同。X 服务器和渲染的功劳都属于 [Termux:X11 的作者们](https://github.com/termux/termux-x11)。
