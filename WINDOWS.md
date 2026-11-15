# Windows 设备（WINDOWS.md）

状态：草稿 v0.1（2026-11-15），还没有写代码。优先级：0.6.0 之后的第二项（通知之后）。
相关：DEVICES.md §8「以后」里的 Windows 一行（`win:<id>`、「添加设备」里现在是「即将支持」），INPUT.md §5.5、DISPLAY.md §3.0。

## 1. 目标

Windows 电脑像 Mac 一样是一台设备：配对一次，它的标签里第一项是「整个桌面」，后面是它的应用；打开任何一项，画面上外屏，键鼠、Dock、卡片、设备小标记、画质三档都和 Mac 一样用。

## 2. 选型

| 路线 | 主机端 | 优点 | 缺点 |
|---|---|---|---|
| **Moonlight 协议**（推荐） | [Apollo](https://github.com/ClassicOldSong/Apollo)（推荐）或原版 [Sunshine](https://github.com/LizardByte/Sunshine) | 显卡硬件编码（N 卡、A 卡、Intel），HEVC/AV1，局域网延迟十几毫秒，带声音；开源；客户端核心 [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) 是 GPL-3.0，可以直接用在 XDesk 里 | 串流单位是一块屏或主机上配置的一个应用，不能挑一个已经开着的窗口 |
| RDP / RemoteApp | Windows 专业版自带 | 不用装主机软件；RemoteApp 能单开一个应用的窗口 | 家庭版不能当主机；延迟画质不如上面；FreeRDP 的 RemoteApp 目前只在它的 X11 后端可用，安卓上没有现成的 |
| RustDesk | RustDesk | 通用、能自建服务器 | 不是为低延迟做的；许可证（AGPL）和 XDesk 混用有麻烦 |

结论：第一版走 Moonlight 协议。RDP RemoteApp 留作以后「不想装主机软件」「要单开一个窗口」的补充。

### 2.1 主机装什么

- **Apollo（推荐）**：Sunshine 的分支，一个安装包。每次串流按客户端（XDesk 外屏）的分辨率、帧率建一块虚拟显示器，串流结束就删掉，Windows 会按客户端记住它的设置。XDesk 外屏多大，Windows 就是多大，不留黑边，笔记本自己的屏幕不受影响，没接显示器的主机也不用假显示器插头。
- **原版 Sunshine**：同一套协议，也支持。串的是真实屏幕：分辨率对不上外屏就留黑边，笔记本的屏幕会被占用，无显示器的主机要假插头。可以加 [sunshine-vdisplay](https://github.com/Zayrick/sunshine-vdisplay) 得到同样的虚拟显示器。
- XDesk 不改主机端，也不 fork 它们。

## 3. 和 Sill 比，按窗口能做到什么程度

- Moonlight 协议里，客户端只能从主机的**应用清单**里选一项启动（清单是主机上配置的：默认有「Desktop」，可以加任何程序），不能列出已经开着的窗口。
- **第一版：整个桌面 + 应用清单。**「整个桌面」= 清单里的 Desktop；其他每一项是一个「应用」，打开时 Apollo 把它开在给 XDesk 新建的虚拟显示器上，效果接近单开一个应用。同时开几个会挤在同一块虚拟屏上（这是协议的边界）。
- **第二步：按窗口（像 Sill）。** 需要 Windows 上装一个 XDesk 小助手（很小的托盘程序，开源，用 Win32 API）：列出开着的窗口和装了的应用，把选中的窗口挪到串流的虚拟显示器上并最大化，窗口关了告诉 XDesk。这就是 Sill 在 Mac 上做的事。小助手 + Apollo = Sill 那种体验。小助手的协议另写。
- 应用清单的同步：Sunshine/Apollo 有带管理员账号的网页接口（`https://主机:47990/api/apps`，列出、添加、删除应用）。XDesk 可以在用户同意并输入一次管理员账号后，把「开始菜单里的应用」一键加进主机的清单；默认只读清单，不改主机。

## 4. 在 XDesk 里

- **添加设备 › Windows**：局域网里自动发现（Moonlight 用 mDNS `_nvstream._tcp`），或手动填地址（经 Tailscale 这类 VPN 时发现不了，要手填并记住）。配对：XDesk 显示 4 位 PIN，用户在主机的网页（`https://主机:47990`）里输入——和 Moonlight 一样。
- **设备**：`win:<主机 id>`，类型 WINDOWS，状态和 Mac 一样（连接、断开、锁定）。标签：整个桌面在最前，后面是应用清单（主机给的封面图当图标）。设备小标记：Windows 的图形（不用微软商标，画一个四格窗）。
- **内容**：`WinSource implements ContentSource`（key `w:<id>:desktop`、`w:<id>:app:<appId>`），和 `MacSource` 平行。
- **画面**：moonlight-common-c（JNI）收流，手机硬解（MediaCodec，HEVC 优先，AV1 看手机），画在外屏的 Surface 上；分辨率、帧率按外屏（DISPLAY.md §4.1 的 b·u·R/N，帧率按外屏刷新率，上限主机允许的）。
- **声音**：Opus，走 XDesk 的声音设置（AudioRoute），和 Mac 一样默认开；拔耳机静音的规则复用。
- **输入**：键鼠直接发给主机（Moonlight 协议有键盘、鼠标、滚轮、触摸、手柄）。Windows 用它自己的输入法（中文由 Windows 的输入法候选），和 Mac 一样「谁显示画面谁拼字」；修饰键不翻译（PC 键盘就是 Windows 的）。Ctrl+Alt+Del 等系统组合键：协议能发，XDesk 不拦。
- **画质三档**：清晰 / 平衡 / 流畅映射到码率、分辨率倍数、帧率；网络变差自动降档的逻辑（`AutoQuality`）复用，信号换成 Moonlight 的丢帧/网络统计。
- **接收端**：Windows 的画面经手机重新编码给接收端；以后可以像 Mac 直通那样直接转发 HEVC。

## 5. 实现步骤

1. moonlight-common-c 编进 XDesk（NDK，带它要求的 ENet 子模块），一个最小 JNI 包装：配对、取应用清单、开始/结束串流、收视频音频、发输入。
2. `WinClient`（连接、配对、状态）、`WinSource`、设备页和「添加设备 › Windows」。
3. 画面、声音、输入接进现有的内容和 Router。
4. 画质三档和自动降档。
5. 文档、发版说明；在一台 Windows 机（Apollo）和一台装原版 Sunshine 的机器上实测。
6. 第二步：Windows 小助手（按窗口）。

## 6. 待定

- 许可：moonlight-common-c GPL-3.0，XDesk 是 GPL（Termux:X11 分支），兼容；发布时附它的许可。
- 手柄：XDesk 现在没有手柄路线，第一版不做，协议支持，以后加。
- HDR：外屏不一定支持，第一版只做 SDR。
