# 文件中转架（SHELF.md）

状态：草稿 v0.1（2026-11-15），还没有写代码。优先级：第三项，在 Windows 设备接入之后。名字「中转架」是暂定的。

## 1. 目标

XDesk 已经是各设备的显示交换台；文件也在这里中转。外屏上一个小窗（中转架）暂存文件、图片和文字：从任何设备放进来，再发到任何设备。没有 XDesk 的设备，只要装了 [LocalSend](https://github.com/localsend/protocol)，也能互传。

## 2. 两种通道，中转架在中间

```
本机：安卓应用 / Termux / proot / chroot ── 直接复制 ──────┐
                                                          ├─ 中转架（在手机上，XDesk 私有目录）
其他设备：Mac / Windows / Linux 电脑 / 手机 / 平板 ── LocalSend ┤
网页端 ── XDesk 自带的网页服务（上传、下载） ────────────────┘
```

- 本机的都在同一台手机上：直接复制最快，不走网络。
- 手机以外的设备统一走 LocalSend：Mac、Windows、Linux 电脑、iPhone/iPad、别的安卓设备都有官方 App；XDesk 自己也成为局域网里的一台 LocalSend 设备，别人的设备装了 LocalSend 就能发给你，不用装 XDesk。
- 网页端当不了 LocalSend 设备（浏览器收不到组播、开不了服务），继续用 XDesk 的网页服务。
- XDesk 接收端（Mac App、安卓接收器）以后也内置 LocalSend（Mac App 是 Rust，可用 Rust 的实现），现有的 FILE_START/DATA/END 消息只为旧版本保留。

## 3. 中转架

- **外屏**：一个小窗，默认停在右下角，可以拖动、收起成一个图标；Dock 里固定一格「中转架」（带数量），快捷键 Ctrl+Alt+S（待定，不和 Ctrl+Alt+M/Q/N 冲突）。
- **手机**：列表里一个「中转架」页签。
- **每一项**：缩略图（图片、视频首帧、PDF 首页）或文件图标，名字、大小、来自哪台设备（设备小标记），收到的时间。可以是文件、一组文件、图片、一段文字或链接。
- **右键 / 长按**：发到某台设备…、打开、存到手机的「下载」、复制（文字）、钉住、删除。
- **存储**：XDesk 私有目录；总上限默认 2 GB（设置里可改）；放进来超过 24 小时自动清理，钉住的不清。

## 4. 放进来

- **安卓应用**：分享菜单「放到 XDesk 中转架」（现在的「发送到电脑」那个分享入口改成它，再从中转架发出去）；第二期：外屏上把文件或图片从安卓应用拖进小窗（`DRAG_FLAG_GLOBAL`）。
- **Linux（Termux / proot / chroot）**：每个环境家目录里一个 `XDesk 中转架` 文件夹（Termux 直接是文件夹；proot、chroot 由 `xdesk` 建一个绑定或同步目录），在文件管理器里拖进去就进中转架；命令 `xdesk shelf add 文件…`、`xdesk shelf list`、`xdesk shelf get 编号 [目录]`。
- **LocalSend**：别的设备发来的文件直接进中转架。来自「我的设备」（配对过、指纹记住的）自动接收；陌生设备先在外屏弹一个确认（复用通知横幅，NOTIFY.md）。
- **接收端**：拖到接收端窗口上的文件进中转架（现在是存到「下载」）。
- **剪贴板**：可选「把复制的图片/长文字也放一份进中转架」，默认关。

## 5. 发出去

- **手机**：存到「下载」、用某个应用打开、分享菜单；第二期：从外屏的中转架拖进安卓应用（`ClipData` + FileProvider 的 content URI）。
- **Linux 环境**：复制到它的「下载」文件夹（或 `XDesk 中转架` 文件夹）；第四期：拖进 X 程序（XDND 桥接）。
- **其他设备**：LocalSend 发送；目标列表 = 局域网里发现的 + 手动添加的 + 已配对的 Mac/Windows（如果它们装着 LocalSend 或以后的接收端）。
- **网页端**：网页里有「中转架」，可下载、可上传。

## 6. LocalSend

- 协议：[LocalSend 协议](https://github.com/localsend/protocol) v2.x，REST over HTTPS（自签证书，用证书 SHA-256 指纹认设备），端口 53317，UDP 组播发现（224.0.0.167），找不到时退回逐个地址注册；可选 PIN。
- XDesk 用 Java 自己实现（不引入 Flutter），参考已有的第三方实现（[localgo](https://pkg.go.dev/github.com/bethropolis/localgo)、[localsend-rs](https://github.com/CrossCopy/localsend-rs)）。
- **跨网段**：经 Tailscale 等 VPN 时组播不通：「添加设备地址」手动填，记住；已配对的 Mac/Windows 已知地址，直接用。
- **后台接收**：只在 XDesk 运行时（前台服务）监听；设置里可关。
- **我的设备**：第一次传成功后可以「记住这台」，以后自动接收。

## 7. 分期

1. 中转架小窗和模型、手机分享进出、Linux 文件夹和 `xdesk shelf`、接收端改为进出中转架。（现有能力的整合）
2. 外屏上和安卓应用之间拖放。
3. XDesk 内置 LocalSend（发现 + 手动地址、收发、我的设备）。做完这步，Mac、Windows、iPhone 等都通。
4. 接收端也内置 LocalSend；和 X 程序之间拖放。

## 8. 待定

- LocalSend 协议文档本身的许可（实现前确认）；官方 App 的许可。
- 名字。
- 大文件（几 GB）：断点续传、进度；第一版按单个文件 4 GB 上限。
