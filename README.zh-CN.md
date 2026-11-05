# XDesk

**不用 root，从安卓手机把应用放到大屏幕上。** XDesk 可以把安卓应用全屏放到外接显示器上，也可以运行 Termux、proot 或 chroot 里的 Linux 桌面。手机屏是控制台：启动器和切换器、触摸板、键盘和设置。安卓应用需要 Shizuku，Linux 桌面不需要。

XDesk 的生命周期和桌面无关。打开或划掉 XDesk 都不会启动或结束 XFCE。Termux 桌面只是启动器里的一项，由你自己启动和结束。

[English](README.md) · 基于 [Termux:X11](https://github.com/termux/termux-x11)（[上游说明](README.upstream.md)）· GPL-3.0

> **现状：** 这是早期的个人项目，每天在一台手机上使用：欧版（EEA）小米 15 Ultra（型号 25010PN30G，骁龙 8 Elite，HyperOS 3，Android 16，BL 未解锁）。别的机型都没有试过，包括这款手机的国行版，其他机型和 ROM 可能表现不同。App 界面目前只有中文。非常欢迎反馈其他机型的结果。

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

> 把脚本交给 shell 之前，请先看一眼：[install.sh](xdesk/install.sh)。APK 用的是上游公开的测试密钥签名，试用没问题，但任何人都能用同一把密钥签出“更新”。

## 需要什么

- 支持 **USB-C 视频输出**（DisplayPort Alt Mode）的安卓手机。插上显示器能镜像就符合
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

在选择界面点一个应用，它就全屏运行在外接屏上，手机做它的触摸板和键盘。实体鼠标和键盘先留给手机，按顶部的**接管键鼠**或 Ctrl+Alt+M 才转给外接屏，再按一次交还。**启动器**把启动器盖在会话上，外接屏不变。**回到触摸板**关掉它。

- **需要 [Shizuku](https://shizuku.rikka.app/)。** 安卓不允许普通 App 给别的 App 注入输入，所以这个 APK 的一个小服务通过 Shizuku 以 shell 用户运行，由它读取 `/dev/input` 并注入事件。Termux 环境不需要 Shizuku。
- **需要悬浮窗权限，** 因为安卓自己的光标留在手机屏上，XDesk 要在外接屏上画一个。
- **Dock 和切换。** 选择界面可以按名字或包名搜索。点一行右边的星加入 Dock。长按 Dock 或列表里的图标，会打开这个应用的卡片：Dock 位置、界面大小、鼠标或手指、关闭应用。Dock 一直留在触摸板界面上，点一下就切换外接屏上的内容，不用回选择界面。已经在运行的应用只是回到前台，状态还在。只要安卓没有把它们关掉，桌面可以在应用后面继续运行，应用的任务也可以留在桌面后面。应用在前面时桌面没有声音。
- **RustDesk 里的光标。** 远程会话中，XDesk 的光标在远程画面上是一个淡淡的小圆点，不妨碍远程光标，在 RustDesk 自己的界面上也看得到位置。在工具栏上和点工具栏后的几秒内是正常光标。判断会话靠的是在一个版本上看到的窗口标志。判断错了就打开“始终显示 XDesk 光标”。
- **安卓的返回键。** **返回 App** 键把 Back 发给外接屏上的应用，给那些靠边缘滑动返回的应用用。鼠标侧键发送 Back 和 Forward。RustDesk 会话中，侧键仍然交给 RustDesk。Back 之后外接屏上没有应用的话，会打开列表；露出后面的另一个应用时，输入方式和界面大小跟着它。
- **两种操作方式（每个应用单独设）。** 键鼠是键盘和鼠标：手机的触摸板、按钮和按键，还有实体键鼠。触控给只听触摸的应用或网页，眼睛一直看大屏幕：一根手指移动光标，轻点就是光标处点按，轻点后按住滑动是拖动，按住不动是长按。两根以上（最多五根）是真正的多指，围绕光标摆放，外接屏上每根手指有一个点，捏合和滑动都能直接用。这时实体鼠标当手指用，左键一根，右键两根，滚轮是滑动。XDesk 记住每个应用的方式，默认键鼠，RustDesk 固定键鼠。
- **触控区全屏。** 触控区角上的图标让它铺满整个手机屏，系统栏隐藏，XDesk 会记住。角上半透明的小图标可以返回 App、打开键盘和退出全屏，手机的返回也能退出。键鼠模式下左右键不显示，轻点、双指轻点和长按拖动能代替。
- **仪表盘。** XDesk 开着又接了外接屏时，外接屏最底下显示时钟和状态，应用退出后露出来的就是它。结束 XDesk 后它消失，外接屏回到镜像。
- **结束会话。** 外接屏一直保持原样，直到你用**结束 XDesk**（在启动器或设置里）结束，或从最近任务划掉 XDesk。这时外接屏上的应用移回手机（不关闭），外接屏回到镜像，键鼠释放，声音路由和界面大小恢复。Termux 桌面继续运行，只是没有窗口，启动器上显示为运行中。移回应用需要 Shizuku，注入服务不在时它们留在外接屏上，XDesk 会提示。回到启动器或手机桌面都不会结束会话：接管的键鼠还在，Ctrl+Alt+M 仍然有效。
- **XDesk 不会自己重启桌面。** X 服务器停了，启动器会说明，点一下环境就能重新启动。
- **外接屏界面大小。** 外接屏上有安卓应用时，可以在设置里填 120 到 220 dpi 作为默认值，按物理显示器记住。应用可以在卡片里有自己的值，启动前和每次切到它时生效。值不同才会改，因为应用要重新布局。光标大小不跟着变，它按显示器高度算。会话结束时恢复，镜像回到正常。桌面有自己的 DPI 设置，所以桌面显示时外接屏回到默认值。需要 Shizuku。
- **只在一台手机上测试过，** 也只试过 RustDesk。
- **RustDesk 的输入方式依赖它的内部实现。** 普通鼠标事件在那里会被 Flutter 弄坏拖动，所以 XDesk 把按键变化作为悬停事件发送，并靠猜测判断一次点击是不是给 RustDesk 自己的工具栏。RustDesk 更新后可能失效。需要特殊方式的应用列在 `AppInputProfile.java`，其他应用用普通的安卓鼠标事件。
- **手机键盘只能发送键盘打得出来的字符。** 中文等输入法文字到不了安卓应用。

## 功能

- 手机当触摸板：移动、轻点、双指右键和滚动，长按或轻点后按住拖动
- 软键盘，支持输入法打字（包括中文），还有快捷键栏（Esc、Tab、Ctrl、Alt、Super、Shift、方向键）
- 实体鼠标和键盘，转发到桌面
- 安卓应用全屏放到外接屏上（需要 Shizuku）
- 声音输出可切换：手机扬声器、HDMI、蓝牙、USB、有线耳机、系统默认。所有模式共用一个设置，切换后依然有效。Shizuku 运行时，它设置系统的首选媒体设备，桌面、安卓应用和其他声音都生效，回到选择界面或退出时清除，下次会话再按你的选择设置。没有 Shizuku 时只能尽力指定桌面声音：在测试手机上，接着 HDMI 时没法把声音拉回手机扬声器。这只是把输出指向那个设备，音质和蓝牙编码没法保证。如果 XDesk 被强杀到来不及清除，在 XDesk 里选一次系统默认，或者重启手机
- 分辨率档位、灵敏度、触控界面横竖屏、闲置时调暗手机屏
- 拔掉显示器、切换 App、锁屏后都能恢复

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

改动几乎都在 `lorie-app/` 和 `xdesk/`，合并上游比较简单。

## 常见问题

日志在 `$PREFIX/tmp/xdesk.log`。

| 现象 | 可能的原因 |
|---|---|
| 一直等待 | 你接上了 X 显示，还没有程序在运行。运行 `xdesk start`，再看 `xdesk status` |
| 选择界面没有环境 | 运行 `xdesk enable-app-start` 和 `xdesk setup`，并允许 XDesk 运行 Termux 命令 |
| 安卓应用列表不全 | 有些 ROM 限制 App 能看到什么。小米手机检查“获取应用列表”权限 |
| 注入服务启动不了 | 打开 Shizuku 确认它在运行。重启手机后要重新启动它 |
| 提示 “Display :1 is used by another X server” | 官方 Termux:X11 在运行，先结束它，比如 `pkill -f termux-x11` |
| App 的启动按钮失败 | 运行 `xdesk enable-app-start`，再检查权限 |
| 桌面没有铺满 | 它在 App 连上之前就启动了。先打开 App，再重启环境 |
| 外接屏闲置几分钟后黑屏 | XFCE 的屏保。动一下鼠标，或者调大 xfconf `xfce4-screensaver` 的 `/saver/idle-activation/delay` |
| 手机屏上出现安卓光标 | 鼠标抓取丢了，点一下 XDesk 的手机界面 |
| 没有声音 | 看顶部的声音输出，很多显示器没有喇叭 |
| 软键盘上 Ctrl + 字母没反应 | 拼音模式下字母留在输入法里，切到英文布局或用实体键盘 |

## 局限

- 安卓应用需要 Shizuku，外接屏同一时间只能放一个全屏应用或桌面，切换要经过选择界面
- Termux 和 XDesk 都要保持运行，系统清理可能杀掉它们
- 只在一台手机（欧版小米 15 Ultra）上测试过

## 构建

默认分支是 `xdesk`，`master` 跟随上游。推送后由 GitHub Actions 构建，`v0.1.0` 这样的标签会发布 Release。

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
