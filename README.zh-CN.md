# XDesk

**不用 root，让安卓手机在外接显示器上跑完整的 Linux 桌面。** 插上显示器，打开 XDesk，大屏上就是 XFCE，手机变成触摸板和键盘。

[English](README.md) · 基于 [Termux:X11](https://github.com/termux/termux-x11)（[上游说明](README.upstream.md)）· GPL-3.0

> **现状：** 这是个人项目，每天在一台手机上使用：小米 15 Ultra（骁龙 8 Elite，HyperOS 3，Android 16，BL 未解锁）。别的机型都没有试过，可能表现不同，尤其是 `xdesk/startx.sh` 里的 GPU 设置。界面目前只有中文。

## 功能

- 外接屏上的 XFCE，通过 Mesa 实现 GPU 加速（Adreno 上是 zink + Turnip）
- 手机当触摸板：移动、轻点、双指右键和滚动，长按或轻点后按住拖动
- 软键盘，支持输入法打字（包括中文），还有快捷键栏（Esc、Tab、Ctrl、Alt、Super、Shift、方向键）
- 实体鼠标和键盘，转发到桌面
- 声音输出可切换：手机扬声器、HDMI、系统默认
- 分辨率档位、灵敏度、触控界面横竖屏、闲置时调暗手机屏
- 拔掉显示器、切换 App、锁屏后都能恢复。如果系统杀掉 X 服务器，桌面会自动重启，但里面未保存的内容会丢

不需要 root，日常使用也不需要 `adb`，可以和官方 Termux:X11 共存，互不影响。

## 工作原理

```
 手机屏                              外接屏
┌───────────────────────┐           ┌───────────────────────┐
│ XDeskActivity         │  直接     │ MainActivity（上游）   │
│ 触摸板、按键、声音    ├──调用────►│ LorieView 显示 XFCE    │
└───────────┬───────────┘           └───────────▲───────────┘
            │ RUN_COMMAND                       │ X11
┌───────────▼───────────────────────────────────┴───────────┐
│ Termux：x11-session.sh → X 服务器、XFCE、PulseAudio        │
└───────────────────────────────────────────────────────────┘
```

- **一个 App，两个窗口。** 上游的 `MainActivity` 原样放在外接屏上。`XDeskActivity` 在手机屏上，和它在同一个进程，直接调用 `LorieView.sendMouseEvent`、`sendKeyEvent`、`sendTextEvent`。
- **X 和 XFCE 仍在 Termux 里**，通过 Termux 的 `RUN_COMMAND` 启动，用的是为这个包名构建的启动器。
- **声音** 从 PulseAudio 的 null sink 走本机 TCP 端口，由 App 用 `AudioTrack` 播放，所以不需要特殊权限就能选输出设备。这个端口只绑定本机，但没有验证，手机上的其他 App 理论上能读到桌面的声音。

改动几乎都在 `lorie-app/`，合并上游比较简单。

## 需要什么

- 支持 **USB-C 视频输出**（DisplayPort Alt Mode）的安卓手机。插上显示器能镜像就符合
- USB-C 转 HDMI 线或扩展坞
- Android 11 或更高（只在 Android 16 上实测过）
- [Termux](https://github.com/termux/termux-app)，并授予存储权限

## 安装

### 1. 安装 Termux 软件包

```sh
termux-setup-storage
pkg upgrade
pkg install x11-repo
pkg install xfce4 xfce4-terminal pulseaudio mesa vulkan-loader mesa-vulkan-icd-freedreno xorg-xrandr dbus util-linux git
```

`mesa-vulkan-icd-freedreno` 用于 Adreno GPU。其他 GPU 要换掉它，并调整 `xdesk/startx.sh` 里的变量。

### 2. 获取构建产物

在 [Actions 页面](https://github.com/n2ray/xdesk/actions/workflows/debug_build.yml) 的**同一次构建**里下载两个产物（需要登录 GitHub）：

- `termux-x11-universal-debug`：APK
- `termux-companion packages`：里面的 `termux-x11-nightly-*.deb` 包含启动器

也可以 Fork 这个仓库，让 Actions 替你构建。本地构建需要 Android SDK 和 NDK：

```sh
git clone --recursive https://github.com/n2ray/xdesk
cd xdesk && ./gradlew assembleDebug
```

> APK 用的是仓库里公开的测试密钥签名。试用没问题，但任何人都能用同一把密钥签出“更新”。

### 3. 安装 Termux 侧

```sh
git clone https://github.com/n2ray/xdesk ~/xdesk
bash ~/xdesk/xdesk/install.sh /path/to/termux-x11-nightly-*.deb
```

它会把脚本复制到 `/sdcard/Termux/xdesk`，把启动器装到 `~/.local/libexec/xdesk/loader.apk`，并设置 `allow-external-apps = true`。这个设置允许 App 调用 Termux 命令，不过每个 App 还要单独获得第 5 步的权限。

### 4. 安装 APK

```sh
adb install -r termux-x11-universal-debug.apk
```

在我们的手机上，用文件管理器安装只报“未安装应用”，`adb install` 才成功。小米手机要先在开发者选项里打开 **USB 安装**。

### 5. 运行

1. 插上显示器
2. 打开 **XDesk**，允许**运行 Termux 命令**和发送通知
3. 几秒后，外接屏上就会出现 XFCE

要结束时，用**设置 → 结束桌面**。未保存的内容会丢。

## 使用自己的 XFCE 配置

如果 `/sdcard/Termux/startx.sh` 存在，XDesk 会优先用它，而不是自带的那份。它运行时会改写下面两行，所以这两行要保持原样：

```sh
termux-x11 :1 -ac -xstartup "dbus-run-session startxfce4" &
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity
```

## 常见问题

日志在 `$PREFIX/tmp/xdesk.log` 和 `$PREFIX/tmp/xfce_startup.log`。

| 现象 | 可能的原因 |
|---|---|
| 一直转圈，然后提示“桌面没有启动” | `allow-external-apps` 没开、权限被拒绝或启动器缺失，看日志 |
| 外接屏闲置几分钟后黑屏 | XFCE 的屏保。动一下鼠标，或者调大 xfconf `xfce4-screensaver` 的 `/saver/idle-activation/delay` |
| 显示器上有黑边 | 安卓报告的比例可能和显示器不同。XDesk 按系统报告的比例渲染来铺满 |
| 手机屏上出现安卓光标 | 鼠标抓取丢了，点一下 XDesk 的手机界面 |
| 没有声音 | 看顶部的声音输出，很多显示器没有喇叭 |
| 软键盘上 Ctrl + 字母没反应 | 拼音模式下字母留在输入法里，切到英文布局或用实体键盘 |
| `X server stalled` | 来自上游的看门狗。不要用 `FLAG_ACTIVITY_CLEAR_TASK` 重建窗口 |

## 局限

- 只能操作 Linux 程序。安卓应用可以放到外屏，但操作不了，因为系统把安卓鼠标限制在手机屏上，普通 App 也不能给别的 App 注入输入
- Termux 和 XDesk 都要保持运行，系统清理可能杀掉它们
- XDesk 和官方 Termux:X11 共用一份 XFCE 配置和 `:1`，不要同时运行
- 只在一台手机上测试过

## 在这个基础上开发

默认分支是 `xdesk`，`master` 跟随上游。

```sh
git remote add upstream https://github.com/termux/termux-x11
git fetch upstream && git merge upstream/master
```

XDesk 依赖上游的 manifest、`com.termux.x11` 下的类名和 `LorieView` 接口。包名在 `lorie-app/build.gradle` 和 `shell-loader/build.gradle` 里设置，两边要一致。

## 许可证

GPL-3.0，和 Termux:X11 相同。X 服务器和渲染的功劳都属于 [Termux:X11 的作者们](https://github.com/termux/termux-x11)。
