# Linux 电脑（LINUX-PC.md）

状态：设想 v0.1（2026-11-15），还没有写代码，什么都没定。公开讨论在 issue #9（https://github.com/n2ray/xdesk/issues/9）。优先级：在 Windows（WINDOWS.md、issue #8）之后；整个桌面那一层和 Windows 共用。
相关：DEVICES.md §2 的设备表、DISPLAY.md（以后的无根 X、Wayland 路线）、SHELF.md（和 Linux 电脑互传文件走 LocalSend）。

这里说的是**真的 Linux 电脑**（台式机、笔记本、家里的服务器），不是手机里的 Termux、proot、chroot。

## 1. 目标

和 Mac 一样是一台设备：加一次，它有自己的标签，第一项是「整个桌面」，后面是它的应用；打开的东西全屏上外屏，键鼠、Dock、卡片、设备小标记、画质三档都和别的设备一样用。

## 2. 两层

Linux 的桌面五花八门（X11 或 Wayland，GNOME、KDE、wlroots 系的合成器……），而 XDesk 自己就带一个 X 服务器，所以分两层做。

### 2.1 整个桌面：Moonlight 协议，和 Windows 共用

Linux 上装 [Sunshine](https://github.com/LizardByte/Sunshine)（X11、Wayland 走 wlroots 或 portal、或 KMS 抓屏，显卡编码）。Windows 那边计划的 Moonlight 客户端（moonlight-common-c 编进 XDesk）原样用于 Linux 主机：配对、应用清单、画质、声音、输入全共用。限制和 Windows 一样：整个桌面，或主机应用清单里的一项，不能挑单个窗口。

### 2.2 单个应用和窗口：画进 XDesk 自己的 X

Termux 里的 X 程序在 XDesk 里已经是一个个独立内容。Linux 电脑上的程序也可以隔着网络画进同一个 X 服务器，于是它就是又一个 X 内容，不用新的画面通路。可选的办法：

| 办法 | 远端要装 | 优点 | 缺点 |
|---|---|---|---|
| **ssh 转发 X**（`ssh -X`） | 只要 `sshd` | 两边都不用装新东西；天然按窗口；XDesk 已经会处理 X 窗口 | X11 走网络很啰嗦，浏览器、视频这类重的程序慢；纯 Wayland 的程序要靠 XWayland |
| **[xpra](https://github.com/Xpra-org/xpra) 无缝模式** | xpra 服务端 | 按窗口，有压缩（H.264/VP9/WebP），带声音和剪贴板，能断线重连 | 手机这边要跑 xpra 客户端（Termux 里或者编进 XDesk），在安卓上跑得怎么样**还没核实** |
| **[waypipe](https://gitlab.freedesktop.org/mstoeckl/waypipe)** | waypipe | Wayland 程序原生，效率高 | 手机这边要有 Wayland 合成器，XDesk 现在没有（DISPLAY.md 里 Wayland 是「以后再评估」） |

初步想法：先做 ssh 转发 X（两边都不用装新东西），要速度的再上 xpra。两种做法 XDesk 都要：
- 用 ssh 密钥连（配对 = 在电脑上加一次 XDesk 的公钥）；
- 从电脑的 `.desktop` 文件读已装的应用和图标，作为设备的标签；
- 在电脑上启动应用、窗口画到 XDesk 的 X 上，和 Termux 程序一样当内容处理。

## 3. 在 XDesk 里

- **添加设备 › Linux 电脑**：地址（或局域网发现）、用户名、加 XDesk 的公钥；电脑上有 Sunshine 的话，另外为「整个桌面」配对一次。
- **设备** `lnx:<host>`，自己的徽章颜色；内容 `l:<host>:desktop`（Moonlight）和 `l:<host>:app:<id>`（单个应用）。
- **输入**：键送进 X，和 Termux 程序一样。远端程序的中文谁来拼（电脑自己的输入法隔着网络，还是手机这边的 fcitx5）没定。
- **声音**：整个桌面由 Moonlight 带；单个应用走网络 PulseAudio/PipeWire 或 xpra 自己的声音，没定。

## 4. 待定（issue #9 里讨论）

- 局域网里 ssh 转发 X 对日常程序够不够用，还是一开始就默认 xpra？
- xpra 客户端在安卓上（Termux 里或内置）跑得怎么样？
- 纯 Wayland 的桌面：单个应用靠 XWayland，还是以后做 waypipe 路线？
- 远端程序的文字输入用远端的输入法还是手机的？
- Linux 电脑和 Windows 电脑要不要共用一种「Moonlight 主机」设备类型，按窗口的能力在 Linux 上靠 X、在 Windows 上靠小助手补上？
