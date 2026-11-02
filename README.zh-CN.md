# XDesk

**不用 root，让安卓手机在外接显示器上跑任意 Linux 桌面。** XDesk 把显示器变成 X 显示器，手机变成它的触摸板和键盘。上面跑什么由你决定：Termux 里的 XFCE、proot 或 chroot 里的桌面，或者单个程序。

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
3. 运行 `xdesk start`。

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

XDesk 默认只等待桌面连上来，不需要任何 Termux 权限。如果想用它的**一键启动**按钮或自动启动设置：

```sh
xdesk enable-app-start
```

这会在 Termux 里设置 `allow-external-apps = true`，允许 App 请求运行命令。每个 App 还要单独获得权限，XDesk 会在你按按钮时询问。

## 功能

- 手机当触摸板：移动、轻点、双指右键和滚动，长按或轻点后按住拖动
- 软键盘，支持输入法打字（包括中文），还有快捷键栏（Esc、Tab、Ctrl、Alt、Super、Shift、方向键）
- 实体鼠标和键盘，转发到桌面
- 声音输出可切换：手机扬声器、HDMI、系统默认
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
- **声音** 从 PulseAudio 的 null sink 走 47130 端口，由 App 用 `AudioTrack` 播放，所以不需要特殊权限就能选输出设备。这个端口只绑定本机，但没有验证，手机上的其他 App 理论上能读到桌面的声音。

改动几乎都在 `lorie-app/` 和 `xdesk/`，合并上游比较简单。

## 常见问题

日志在 `$PREFIX/tmp/xdesk.log`。

| 现象 | 可能的原因 |
|---|---|
| 一直等待 | 还没有程序在运行。运行 `xdesk start`，再看 `xdesk status` |
| 提示 “Display :1 is used by another X server” | 官方 Termux:X11 在运行，先结束它，比如 `pkill -f termux-x11` |
| App 的启动按钮失败 | 运行 `xdesk enable-app-start`，再检查权限 |
| 桌面没有铺满 | 它在 App 连上之前就启动了。先打开 App，再重启环境 |
| 外接屏闲置几分钟后黑屏 | XFCE 的屏保。动一下鼠标，或者调大 xfconf `xfce4-screensaver` 的 `/saver/idle-activation/delay` |
| 手机屏上出现安卓光标 | 鼠标抓取丢了，点一下 XDesk 的手机界面 |
| 没有声音 | 看顶部的声音输出，很多显示器没有喇叭 |
| 软键盘上 Ctrl + 字母没反应 | 拼音模式下字母留在输入法里，切到英文布局或用实体键盘 |

## 局限

- 只能操作 Linux 程序。安卓应用可以放到外屏，但操作不了，因为系统把安卓鼠标限制在手机屏上，普通 App 也不能给别的 App 注入输入
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
