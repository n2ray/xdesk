# XDesk

<p align="center"><img src="docs/img/hero.svg" width="760" alt="大屏上是 XDesk 的启动台，旁边的手机当触摸板，前面是键盘鼠标"></p>

**又一个手机接任何屏幕的软件。** 手机用 USB-C 插上显示器，或者经 Wi-Fi 连上投影、平板、电视、车机，XDesk 就把那块屏变成一个桌面：有启动台、Dock、通知和光标。手机当它的触摸板、键盘、手柄或遥控器。不用 root，也不用另装别的。

**[网站](https://n2ray.github.io/xdesk/?lang=zh)** · [下载](https://github.com/n2ray/xdesk/releases/latest) · [English](README.md) · [使用手册](MANUAL.zh-CN.md) · GPL-3.0

## 屏上能放什么

| | 能得到什么 | 需要什么 |
|---|---|---|
| **安卓应用**，一个一个全屏上大屏 | 手机里的应用、游戏、云游戏放到大屏上，用键鼠操作，手机腾出来干别的 | XDesk 自带注入服务，无线调试里配对一次（或用 Shizuku） |
| **内置 Linux**：Ubuntu 26.04 加 XFCE | XDesk 里自带一个真正的 Linux 桌面：apt 随便装，骁龙 GPU 加速，Google Chrome 视频硬解（VP9、H.264、HEVC、AV1），有声音，能用手机输入法。折腾坏了？一分钟重置，主目录保留 | 不用另装：XDesk 自己运行，第一次下载约 470 MB |
| **你的 Mac**，两种连法 | **屏幕共享**：整个 Mac 桌面按你的屏幕大小显示，带声音，HEVC 或 H.264，Mac 上什么都不用装。**[Sill](https://github.com/Saffsanity/sill)**：Mac 的窗口和应用一个个放上来，用 Mac 自己的输入法打字，锁着的 Mac 也能解锁 | 屏幕共享：Apple 芯片、macOS 14 以上、打开屏幕共享。Sill：Mac 上装 Sill |
| **Termux 里的 Linux** | 已经在用 [Termux](https://github.com/termux/termux-app)、或想用别的发行版：proot、chroot 的桌面和程序，骁龙 GPU 加速（Turnip、zink），和内置 Linux 并存 | Termux 加 XDesk 的安装脚本 |

在启动台里切换：内置 Linux、Termux、Mac 和安卓应用可以同时开着，大屏显示你选的那个。手机这边可以选「操作台」（触摸板为首页）或「启动台」（应用网格为首页），横竖屏都有。

## 能接哪些屏

| 用法 | 接法 | 手机当什么 |
|---|---|---|
| **当电脑用**：显示器 + 键鼠 | USB-C 视频输出，插上就行 | 启动台或触摸板；键鼠直接用 |
| **投影娱乐**：看片、打游戏 | 投影或电视盒子装接收器，Wi-Fi | 手柄（游戏当真手柄认）或遥控器 |
| **平板放大**：手机应用、手游 | 平板装接收器，Wi-Fi | 直接在平板上点，多指也行 |
| **浏览器当显示器** | 设置 › 网页显示，iPad、电脑、智能电视打开它给的地址 | 什么都不用装；USB 共享网络比 Wi-Fi 延迟更低 |
| **车机**：停车时办公、看视频 | 只支持 Android Auto（不支持 CarPlay），只能停车时用 | 触摸板和键盘 |
| **手机放进 Mac** | Mac 版 XDesk，数据线或 Wi-Fi：这时 Mac 是屏幕 | Mac 窗口里的手机；剪贴板和文件两边通 |

## 还能这样用

| 用法 | 怎么做 | 实测 |
|---|---|---|
| **云游戏**：GeForce NOW 等 | 大屏开云游戏，接手柄；键鼠游戏按 Ctrl+Alt+G，键鼠直通给游戏，转视角、WASD 和电脑上一样 | GeForce NOW：蓝牙手柄、键鼠直通都流畅，USB-C 显示器和投影接收器上都试过。Steam Link、Moonlight 应该也行，还没试 |
| **手机当手柄** | 大屏开着安卓应用时点「手柄」，手机横过来就是手柄，布局可以改 | 游戏认成有线 Xbox 360 手柄，GeForce NOW 里也能用 |
| **远程桌面**：RustDesk | 在大屏上开 RustDesk，用键鼠或手机触摸板操作远端电脑 | 拖动、侧键、光标都专门处理过 |
| **内置 Linux 不接屏也能用** | 在它的页面里打开 SSH 访问：只认密钥，默认只听本机，允许后才听局域网 | 熄屏照样跑；通知栏可以让手机保持唤醒 |
| **声音想去哪就去哪** | 手机喇叭、HDMI、蓝牙、USB、有线耳机，一个设置管所有模式 | 无线接收器和网页显示时，声音在那边放，手机静音 |

## 怎么用

**1. 安装。** 从[发布页](https://github.com/n2ray/xdesk/releases/latest)下载 `xdesk.apk` 装上。安卓 11 及以上。

**2. 按清单开权限。** 第一次打开，XDesk 列出要开的几项，一项项带你开：连上 Wi-Fi，在开发者选项里打开「无线调试」配对一次，再允许悬浮窗。也可以用 [Shizuku](https://shizuku.rikka.app/)。手机重启后要重新激活。

**3. 接上屏幕。**

| 屏幕 | 怎么接 |
|---|---|
| 显示器 | USB-C 线插上。能镜像手机画面就说明支持 |
| 投影、电视、平板、旧手机 | 装 [xdesk-receiver.apk](https://github.com/n2ray/xdesk/releases/latest/download/xdesk-receiver.apk)（安卓 5 起）并打开，手机上 设置 › 连接接收器 |
| 浏览器 | 手机上 设置 › 网页显示，在浏览器里打开它列出的地址 |
| 车机 | 在 Android Auto 里打开 XDesk，停车时用 |
| 拿 Mac 当屏幕（Apple 芯片） | 下载 [XDesk-macos-arm64.dmg](https://github.com/n2ray/xdesk/releases/latest/download/XDesk-macos-arm64.dmg)，Mac 上装 [Sill](https://github.com/Saffsanity/sill)；插线自动连上，或在手机上选这台 Mac |

**4. 加上你要的东西。** 安卓应用已经在了，其余的都在 **设备 › 添加设备** 里：

| 添加 | 怎么做 |
|---|---|
| **内置 Linux** | Linux › 内置 Linux › 安装。Ubuntu 加 XFCE，不要 Termux |
| **Mac：屏幕共享** | Mac › 共享桌面：先在 Mac 上打开 系统设置 › 通用 › 共享 › 屏幕共享，再在 XDesk 里填地址和账户。密码在 XDesk 自己的卡片里输，可以用指纹保存 |
| **Mac：Sill** | Mac 上装原版 [Sill](https://github.com/Saffsanity/sill)（不用装 XDesk），手机上 Mac › Sill 连接，输 Sill 显示的配对码或扫码 |
| **Termux 里的 Linux** | 装好 [Termux](https://github.com/termux/termux-app)，在里面运行下面这行（建议先[看看脚本](xdesk/install.sh)），再选 Linux › Termux |

```sh
curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
```

**快捷键**（键盘接在手机上时）：

| 按键 | 作用 |
|---|---|
| Ctrl+Alt+M | 键鼠在外屏和手机之间切换 |
| Ctrl+Alt+N | 打开通知中心 |
| Ctrl+Alt+G | 游戏直通：键鼠直接交给游戏 |
| Ctrl+Alt+Space | 打开 Mac 应用的菜单 |
| Ctrl+Alt+Q | 切换 Mac 画质 |

更多：排错、Linux 命令、各种屏的细节、原理和构建，都在[使用手册](MANUAL.zh-CN.md)里。

## 坦白从宽：要哪些权限

装之前先说清楚。第一次打开时 XDesk 也会列这张清单，之后在 设置 › 权限 随时能看到每一项开没开。

| 权限 | 级别 | 拿来干嘛 | 不给会怎样 |
|---|---|---|---|
| 无线调试（或 [Shizuku](https://shizuku.rikka.app/)） | 必需 | 起一个 shell 身份的小服务，把键鼠和触摸送进大屏上的安卓应用 | 安卓应用在大屏上只能看不能动 |
| 显示在其他应用上层 | 必需 | 在大屏上画光标和启动台 | 大屏上没有光标，基本没法用 |
| 通知 | 推荐 | 配对码直接在通知里输；服务或内置 Linux 在跑时有常驻通知 | 得进 App 里输配对码 |
| 通知使用权 | 用到再开 | 把手机通知搬到大屏上 | 通知只在手机上响 |
| 运行 Termux 命令 | 用到再开 | 一键开 Termux 的桌面 | 不用 Termux 就不用管 |
| 相机 | 用到再开 | 扫码添加 Mac | 手输地址也行 |
| 勿扰 | 用到再开 | 大屏上的勿扰开关 | 那个开关点不了 |
| 自启动（小米等） | 用到再开 | 别让系统掐掉通知转发 | 大屏上的通知时有时无 |

**没要的：** root、无障碍、存储、电话、位置、通讯录。

**说实话的部分：**
- 无线调试配对后，XDesk 的服务和 adb 一样是 shell 身份：能注入输入、建虚拟屏幕、调声音输出，碰不到别的应用的私有数据。只有 XDesk 自己能调用它，重启手机、更新或卸载 XDesk 后就停。配对的钥匙一直有效，直到你在开发者选项里撤销。
- 内置 Linux 放在 XDesk 自己的应用存储里，以 XDesk 的身份运行，看不到别的应用的数据。它的 `sudo` 不问密码；SSH 默认关着，你打开才有。
- 代码里还留着从 Termux:X11 继承来的一个无障碍服务（拦截系统快捷键用），默认关着，XDesk 不会让你开。
- 无线接收器、网页显示和 Mac 的 Wi-Fi 连接，画面、声音和输入都不加密，只在你信得过的网络上用。

## 声明和感谢

- **只在很少的设备上测过。** 我每天用的是欧版小米 15 Ultra（安卓 16），Mac 这边在一台 M4 Mac 上测。内置 Linux 是按骁龙 8 Elite（Adreno 830）调的，其他骁龙走通用 GPU 路径，其他 GPU 用软件渲染。别的手机不一定一样，欢迎[告诉我](https://github.com/n2ray/xdesk/issues)结果。
- **无线、网页和 Mac 的 Wi-Fi 连接没有加密**，只在信得过的网络里用。**车上要先停车**，开车时不要用。

**我和AI一起做的XDesk，在我有的设备上进行了测试，也在日常使用。本软件没有质保，没有客服，使用后果自负。** 如果 XDesk 对你有用，可以在 [Ko-fi](https://ko-fi.com/n2ray) 请我喝杯咖啡。

**感谢**

- [Termux:X11](https://github.com/termux/termux-x11)：XDesk 基于它，X 服务器和渲染都是它的作者们的功劳（GPL-3.0，[上游说明](README.upstream.md)）。
- [Sill](https://github.com/Saffsanity/sill)（Noah Saffer）：Sill 连接靠它；XDesk 不是 Sill 官方应用（Apache-2.0）。
- [libinput](https://gitlab.freedesktop.org/libinput/libinput)：触摸板的双指滚动、缩放和轴锁定规则（MIT）。
- [termux_on_dex](https://github.com/PietroCav501/termux_on_dex)（PietroCav501）：X 服务器里的 GPU 绘制 glamor（GPL-3.0）。

XDesk 以 GPL-3.0 开源，许可证全文和第三方许可在 [LICENSE](LICENSE) 和 [LICENSES/](LICENSES/)。
