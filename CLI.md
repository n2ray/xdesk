# xdesk 命令（CLI.md）

状态：方案，2026-11-16，随 0.6.0 测试版做。
相关：DEVICES.md（环境 = 设备）、INPUT.md §5.2（输入法）、CURSOR.md（光标主题）。

`xdesk`（仓库 `xdesk/xdesk`，装在 `$PREFIX/bin/xdesk`）是 XDesk 和 Termux 之间的桥：XDesk 只当显示器，Termux 原生、proot、chroot 里的桌面和程序都由它在对应的环境里拉起来。X 服务器的代码在 App 里：loader 每次启动 X 时加载已装的 XDesk，所以 App 更新后 X 重启一次就是新代码；会落后的只有 `xdesk` 这个脚本。

这一轮做三件事：版本对齐（§1）、给用户和给 App 的接口分开（§2）、最大兼容（§3）。

## 1. 版本对齐

- **版本号写进脚本。** 脚本里 `XDESK_CLI_VERSION=dev`；CI 发版时把 `out/xdesk` 里这一行改成发版的版本号（如 `0.6.0-beta.5`）。自己从仓库拷的脚本是 `dev`，App 不对 `dev` 提示。
- **`xdesk version`**（也认 `--version`、`-V`）：打印 `xdesk 0.6.0-beta.5`。`xdesk status` 多一行 `xdesk: 0.6.0-beta.5`。
- **版本线**（用户 2026-11-16 定，免得抓乱）：`xdesk update` 不带参数时跟着现在这条线走：命令是测试版（版本号带 `-`，或 `dev`）就装最新的一个（测试版或正式版谁新装谁，所以正式版发布后测试用户自然升上去），是正式版就装最新的正式版（GitHub 的 latest，永远不是测试版）。`xdesk update beta`、`xdesk update stable` 换线，`xdesk update 0.6.0-beta.5` 装这一个（App 用的就是这种）。安装脚本不带 `XDESK_VERSION` 时装最新正式版，`XDESK_VERSION=beta` 装最新的一个；App 复制的安装命令总是带 App 自己的版本。
- **`xdesk update [beta|stable|VERSION]`**（以下为最初的说明，以上面的版本线为准）：重新运行安装脚本，只更新命令（`XDESK_CLI_ONLY=1`：不下载 APK、不问 allow-external-apps、不跑 setup 以外的东西；loader 也换，便宜且无害）。不给版本就装最新的 Release（含测试版），给了就装 `vVERSION`。没网、校验不过时什么都不换，退出码非 0。
- **App 这边：**
  - 读到的命令版本比 App 的 `versionName` 旧（不是 `dev`；比 App 新的不提示，否则「更新」会把它降回去），或者命令太旧报不出版本：Termux 那一栏显示「xdesk 命令是 X，App 是 Y」和一个「更新命令」按钮。
  - 按钮：命令认 `update` 就经 RUN_COMMAND 后台跑 `xdesk update Y`，完了重新读状态；不认（太旧）就走现在的「复制安装命令、打开 Termux」，安装命令带 `XDESK_VERSION=vY`。
  - **X 服务器是 App 更新前启动的**：App 本来就会处理（`checkServerBuild`：连上 X 后问它的构建号，不同就问要不要重启桌面，带「重启」按钮），这里不另加提示。JSON 里的 `x.started` 留着，App 不读。

## 2. 两套接口

**给用户的**（`xdesk`、`xdesk help` 只列这些）：

| 命令 | 作用 |
|---|---|
| `start [NAME]`、`stop`、`switch NAME`、`status` | 桌面会话 |
| `run CMD…`、`x` | 任意程序；只起 X 和声音 |
| `apps`、`launch ENV/ID` | 单个程序 |
| `setup`、`list`、`add`、`rm`、`use` | 找环境、管会话 |
| `keyboard layout [NAME\|auto]` | XDesk 这台 X 的键盘布局（§5.5）；`ime layout` 是给 App 留的旧名字 |
| `ime` | 只读：XDesk 的显示上现在有没有输入法在运行（§5.5） |
| `doctor` | 体检（§3.3） |
| `version`、`update` | §1 |

**给 App 的**：`xdesk help all` 才列出，照旧能用：`scale`、`envs`、`recover`、`enable-app-start`、`apps --path`、`launch --startup-id`。X 服务器（XWindows）调的 `apps --path`、`launch`、`scale` 名字**永远不改**：它调的是启动 X 的那个脚本，可能比 App 旧，也可能比 App 新。

**新增 `xdesk app state`**：App 一次读全，JSON 一行，代替现在的 `list; echo ---; status; echo ---; envs` 三段文字：

```json
{"format":1,"version":"0.6.0-beta.5",
 "sessions":["termux-xfce","proot-debian-xfce"],"default":"termux-xfce","running":"termux-xfce+apps",
 "x":{"running":true,"started":1763290000},
 "ime":true,"layout":"de",
 "envs":[{"name":"termux","kind":"termux","apps":true,"sessions":["termux-xfce"]},
         {"name":"proot-debian","kind":"proot","apps":true,"sessions":["proot-debian-xfce"]}]}
```

- `running`、`default`、`layout` 没有时是 `null`；`x.started` 是 X 进程启动的 Unix 秒，没在跑时 `null`。
- `ime` 只读：XDesk 的显示上现在有没有 fcitx5 在跑（谁启动的都算）。XDesk 不启动、不停止、不配置 X 这边的输入法。
- 字符串按 JSON 转义（引号、反斜杠、控制字符）。
- App 发的命令是 `xdesk app state 2>/dev/null || { 老的三段命令; }`：旧脚本不认 `app`，退出码非 0，同一次 RUN_COMMAND 里退回老格式，App 两种都会解析。输出以 `{` 开头就按 JSON 读。
- 以后 App 要的新东西加进 JSON，不再往 `status` 的文字里加行。

## 3. 最大兼容

### 3.1 环境不必有桌面
现在 `setup` 只在 proot 容器里找到 XFCE、LXQt、MATE 时才记下它，只装了 Firefox 的容器根本不出现。改成：
- 每个 proot-distro 容器都是一个环境（`proot-<distro>`），有没有桌面都在 `xdesk envs`、`xdesk apps` 里，能单独开程序。没有桌面的环境，单开程序时用 Termux 的 xfwm4 管窗口（已有的退路）：App 启动的 `NAME+apps` 对 `envs/` 里的环境也成立。
- 记在 `~/.config/xdesk/envs/NAME.root`（根目录）和 `NAME.launch`（进入环境运行一条命令的前缀），和 `xdesk add --root/--launch` 写的一样；会话（`sessions/*.sh`）是环境里的桌面，可以没有。
- 有桌面的容器照旧按会话列（`proot-debian-xfce` 一行），为了和旧 App 兼容；`envs/` 里同一个根目录的条目不再另列一行。没有桌面的才以 `proot-debian` 出现。
- `xdesk add NAME --root DIR --launch 'PREFIX'` 不给命令（没有 `'CMD'`）时只登记环境。

### 3.1.1 只装 Termux 就能用（用户 2026-11-16：让用户尽可能少操心）
- **窗口管理器随安装脚本装上**：`xfwm4`、`xfce4-settings`（xfsettingsd）。单开程序要靠它让窗口铺满外屏，没有它起不来，所以 `doctor` 里缺 xfwm4 算 `✗`。
- **自动发现**：`xdesk app state` 和 `xdesk envs` 每次先悄悄做一遍 `setup` 的查找（`discover`，只看几个目录，很便宜）。之后才装的 proot 容器、才装的桌面，App 下次读状态就有，不用再跑 `xdesk setup`。
  - `xdesk rm NAME` 删掉的记进 `~/.config/xdesk/ignored`，自动发现不再把它加回来；`xdesk setup`（用户明确要找）清空这个单子，`xdesk add NAME` 把这个名字移出。
  - 容器被 `proot-distro remove` 删掉后，`setup` 为它写的环境和桌面会话（内容和 setup 写的一模一样的）自动删掉；用户自己写的不动。
- **X 没跑过时的程序列表**：App 的程序列表平时由 X 服务器给（带图标），可没有桌面的用户没有东西会先启动 X，列表就一直是空的。所以 X 不在、App 手上也没有列表时，App 经 RUN_COMMAND 跑 `xdesk app catalog`：同一份列表去掉图标那一列（几 KB）。先显示名字，第一次开程序、X 起来后换成带图标的。
- **为什么 App 不能替用户装 xdesk**：Termux 默认拒绝别的 App 让它执行命令，要 Termux 里 `allow-external-apps = true`，这个只能在 Termux 里改。所以第一次必须在 Termux 里粘贴运行一次安装命令（App 会复制好、打开 Termux）；之后读环境、起桌面、开程序、更新命令都由 App 自动做。

### 3.2 认更多桌面
Termux 和容器里都找：XFCE、LXQt、MATE，加上 LXDE（`startlxde`）、KDE Plasma X11（`startplasma-x11`）、Openbox（`openbox-session`）、i3（`i3`）。

### 3.3 `xdesk doctor`
一条命令查出常见问题，每项一行 `✓`/`!`/`✗` 加一句怎么修，有 `✗` 时退出码 1：
- 命令版本、loader 在不在；
- `allow-external-apps`（没开 App 就用不了 Termux）；
- 必要的命令（`need_tools` 那些）、pulseaudio；
- X：在不在跑、socket 在不在、是不是 App 更新前启动的（看 X 进程映射的 `base.apk` 是否已被删掉：App 更新会换掉它；不用 `pm`，Termux 身份跑它会卡住）；
- 窗口管理器：Termux 里有没有 xfwm4；
- 中文字体：有 `fc-list`（在 `fontconfig-utils` 里，很少有人装）就用 `fc-list :lang=zh`，没有就按常见中文字体的文件名找；都没有就提示 `pkg install otf-noto-sans-cjk`；
- 每个环境：根目录在不在、进入命令能不能用（`proot-distro` 装没装）、有几个程序、有几个桌面；
- X 这边的输入法（只报状态，不算错；XDesk 不启动、不配置它，用不用 fcitx5 是用户自己的事）。

### 3.4 认不出的环境
chroot 要 root 才看得到，Andronix、AnLinux 和自己写的 proot 脚本各不相同，不自动登记。`setup` 结尾说一句：「别的 proot 或 chroot：xdesk add NAME --root DIR --launch '进入命令'」，`doctor` 同样提示。

## 4. 测试
`xdesk/test/cli-test.sh`：假的 `$HOME`、`$PREFIX`、proot-distro 容器（只建目录和 `.desktop` 文件），不起 X，查 `setup`、`envs`、`apps`、`app state`（用 jq 校验 JSON）、`version`、`help`、`doctor` 的输出。

## 5. 单开程序：声音、GPU、启动前检查、说清楚为什么起不来（beta.5）

用户 2026-11-16 定：单开程序要处理好声音、GPU、冲突和退路，起不来时给出清楚的原因。

### 5.1 启动前检查
`xdesk launch` 在打印 `pid N` 之前，先看 `Exec=` 的程序在不在那个环境里：
- 去掉开头的 `env`、`VAR=值`，取第一个词；
- 绝对路径就查 `根目录 + 路径`；否则在那个环境的 PATH 目录里找，容器是 `/usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin /usr/games`，Termux 是 `$PREFIX/bin`；
- 是 shell 内建或者找不到根目录（如读不了的 chroot）就跳过检查。

找不到就不启动，输出一行 `xdesk-error not-installed 命令` 后退出 1。App 把 `xdesk-error 代码 说明` 翻成本地化的话（§5.4）；旧 App 照原样显示这一行。

### 5.2 GPU
- **Termux**：XDesk 自己的启动环境，有 Vulkan 驱动（Turnip）就设 zink 相关变量（`ZINK_DESCRIPTORS`、`MESA_GLTHREAD`、`MESA_VK_WSI_PRESENT_MODE`、`vblank_mode`），没有时 Mesa 自己用软件渲染。这几行写在用户会话文件的前面，所以用户自己写的同名 `export` 在后面，会盖掉它们；XDesk 不加 `LANGUAGE` 之类的语言变量，语言由用户的 locale 和会话文件决定。
- **容器**：根目录里有 freedreno 的 Vulkan ICD（`usr/share/vulkan/icd.d/*freedreno*`）就设和 Termux 一样的变量；没有就什么都不设（软件渲染）。`doctor` 每个环境报一行「GPU：硬件（Turnip）/ 软件渲染」，软件渲染时提示可以装 Turnip（Debian/Ubuntu 一类给出包名提示即可，不替用户装）。
- **退路**：程序启动后 10 秒内退出、退出码非 0，且输出里有 GPU 初始化失败的特征（`EGL`、`GLX`、`glXCreateContext`、`Failed to create .*context`、`zink`、`vkCreateInstance`、`MESA.*error`、`kgsl` 一类），又不是已经在用软件渲染：用 `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe` 再起一次，状态里记下 `retried=software`。只重试一次。

### 5.3 声音
已有：Termux 里接本机 PulseAudio，容器和 chroot 经 127.0.0.1 带 cookie。补：`doctor` 查每个容器里有没有 PulseAudio 客户端库（`libpulse.so.0`，在 `usr/lib`、`usr/lib/*-linux-gnu`、`usr/lib64` 下找）。没有就提示装哪个包，按容器 `etc/os-release` 的 `ID`：debian/ubuntu `apt install libpulse0`，arch `pacman -S libpulse`，fedora `dnf install pulseaudio-libs`，alpine `apk add libpulse`，其他只说「装 PulseAudio 的客户端库」。

声音是 XDesk 给用户的一块声卡，但守护进程和它的默认输出是用户的：`start_audio` **不改默认 sink，也不移动任何流**。每个由 xdesk 拉起的会话和程序（Termux 会话、`+apps`、`run`、`launch`、proot 和 chroot 的环境）都在环境里拿到 `PULSE_SINK=xdesk`，所以只有它们新起的流进 XDesk 的 null sink，用户自己的播放器和其他 Termux 程序照旧从原来的输出出声；崩溃也不会在守护进程或 `~/.config/pulse` 里留下改过的默认值。

TCP 模块只复用、不卸别人的：已经有 `module-native-protocol-tcp` 监听 `127.0.0.1` 时直接用；没有才加载 xdesk 自己的，记下模块 id（`$RUN/pulse.tcp`），`stop_audio` 只卸这个记录过的 id。旧版 xdesk 留在 47130 端口上的 `module-simple-protocol-tcp` 照旧清掉。cookie 副本 `$PREFIX/tmp/xdesk-pulse-cookie` 每次 start 重新生成：没有 chroot 环境时 0600，配了 chroot（会话或 `NAME.launch` 里有 `chroot`/`unshare`/`nsenter`）时 0644，让那个系统里的用户能读；`stop_audio` 删掉它。xdesk 自己起的守护进程照旧整个停掉。

### 5.4 说清楚为什么起不来
现在程序起来以后自己退出了（找不到、缺库、闪退），App 要等 15 秒才说「没有打开窗口，日志见 xdesk.log」。改成：

- **每次启动一个外壳**：`xdesk launch` 不再直接 `exec` 程序，而是起一个外壳进程（打印的就是它的 pid，程序是它的子进程，App 按进程树认窗口照样成立）。外壳把程序的输出写到 `$RUN/launch/PID.log`（同时照旧追加到 `xdesk.log`），程序退出后写 `$RUN/launch/PID.status`：
  ```
  exit=127
  reason=not-found
  detail=firefox
  retried=software        （只在 §5.2 重试过时有）
  ```
  `reason` 由外壳按退出码和输出最后几行判断：`not-found`（127，或 `command not found`、`No such file or directory`）、`missing-lib`（`error while loading shared libraries: 库名`，detail 是库名）、`no-display`（`cannot open display`、`Can't open display`）、`gpu`（§5.2 的特征，重试后仍失败）、`crash`（被信号杀掉，128+N，detail 是信号名如 SIGSEGV）、`error`（其他非 0，detail 是输出最后一行有内容的话，截到 200 字）、`ok`（退出码 0）。`$RUN/launch` 只留最近 20 次。
- **X 服务器多一个接口**：`ICmdEntryInterface.launchStatus(int pid)`，加在最后（旧 X 服务器回 null）。返回 `PID.status` 的内容，后面接一行 `--` 和日志最后 20 行；程序还在跑或没有记录就回 null。
- **App**：开程序后每秒问一次，直到窗口出现或 15 秒超时。问到程序已退出且 `reason` 不是 `ok`，就马上结束等待，按 `reason` 说人话：
  - `not-found`：「%1$s 没装好：找不到 %2$s」
  - `missing-lib`：「%1$s 缺少库 %2$s，重装这个程序试试」
  - `no-display`：「%1$s 连不上显示，停掉桌面再开一次试试」
  - `gpu`：「%1$s 的图形初始化失败（换成软件渲染也不行）」
  - `crash`：「%1$s 崩溃了（%2$s）」
  - `error`：「%1$s 退出了：%2$s」
  - `ok` 且没有窗口：「%1$s 运行完就退出了，没有打开窗口」（命令行程序或者已经在别处运行的单实例程序）
  - `retried=software` 且窗口出来了：提示一次「%1$s 用软件渲染打开了（GPU 不可用）」。
  - 日志最后几行写进 logcat（`XDeskLinux`），方便查。
- `xdesk launch` 启动前就失败的（§5.1、找不到程序、进不了环境），照旧打印一行原因后退出；`xdesk-error 代码 说明` 的代码同样按上面翻译（`not-installed` 用 `not-found` 的说法）。

### 5.5 X 服务器、显示号和 X 这边的输入法（2026-11-19）

- **显示号是 `:9`**：官方 Termux:X11 和用户自己的 `startx.sh` 都用 `:1`，XDesk 不再和它们抢。环境变量 `XDESK_DISPLAY=:N` 可以换（第二个 XDesk、测试），只接受 `:数字`，别的值直接报错退出。
- **只动自己的 X**：`x_is_ours` 确认 `$DPY` 上应答的 X 就是 xdesk 起的 `xdesk-x11`（`x_pid`，并且显示的锁文件里也是这个 pid）。`setxkbmap`、`xrandr --dpi`、`xrdb -merge`、`scale`、键盘布局都先过这一关；`$DPY` 上是别人的 X 时什么都不写。`xdesk start` 发现 `$DPY` 被别的 X 占着会拒绝并说明；`xdesk status` / `app state` 也不把它当成 XDesk 的 X。
- **从 `:1` 搬过来**：`xdesk stop` 照旧按进程名 `^xdesk-x11 ` 结束所有 XDesk 的 X，包括还在 `:1` 上的旧版；`xdesk start` 会先结束旧版留在 `:1` 上的 `xdesk-x11`（只认这个名字和 `:1`，别的 `XDESK_DISPLAY` 上的第二个 XDesk 不动）。
- **X 服务器的环境**：`LD_PRELOAD`、`LD_LIBRARY_PATH` 和官方启动器用的 `TERMUX_X11_XSTARTUP` 都不带进去；只有 X 服务器自己 `renice -10`（Chromium 的渲染进程是 -8，X 要排在它前面），会话和程序都是默认优先级。
- **X 怎么结束的有记录**（0.6.7）：X 由一个小 shell 启动并等着它，结束时往 `xdesk.log` 写一行 `X server :9 ended: exit status N` 或 `killed by signal N (名字)`。之前 X 无声消失时查不出原因（2026-11-19）。停止的方式不变。
- **X 这边的输入法归用户**：XDesk 不启动、不停止、不配置、不监视 fcitx5 或其他 X 输入法。`xdesk ime` 只读地报告显示上有没有 fcitx5 在跑（App 用它决定实体键盘是原样发键还是走手机输入法）；`xdesk ime layout` 仍是 App 调的名字，实际是 `xdesk keyboard layout`：把布局存进 `~/.config/xdesk/keyboard-layout`，用 `setxkbmap` 设在 XDesk 自己的 X 上（X 起来时，以及改的时候）；`auto` 取消。用户自己的 fcitx5 profile 不再被改写。手机的输入法始终经 XDesk 的文字通道进 X，与 X 这边有没有输入法无关。
- **不改用户的会话文件**：旧会话缺的 `PULSE_COOKIE`/`PULSE_SINK` 只在 `$RUN` 的运行时副本里补，`sed -i` 不再碰 `~/.config/xdesk/sessions`；旧版 setup 冻结进文件的环境行也原样留着（文件里的行在后面，本来就盖过 XDesk 加的）。
- **读命令不删文件**：`app state`、`envs`、`app catalog` 只做新增式的 `discover`；容器暂时不在时它生成的会话和环境文件留着，等 `xdesk setup`（用户明确要找）或 `add`/`rm` 再处理。
- **apps 总线**：`+apps` 和生成的 Termux 桌面共用的长期 D-Bus 总线只活到没有会话、也没有 xdesk 起的程序为止（会话结束、最后一个程序退出、`xdesk stop` 都会检查并关掉；桌面自己结束时，App 下一次读 `app state` 也会检查），不会在桌面结束后把 xfconfd 之类留在用户另起的桌面旁边。
- **`+apps` 的外观**：容器（proot/chroot）的 `+apps` 只起 `xfwm4`，不起 `xfsettingsd`，Termux 的 XFCE 主题、DPI 和字体不会盖到容器的程序上；Termux 自己的 `+apps` 照旧带 `xfsettingsd`。
- **会话文件是用户的 XDesk 启动脚本**：`~/.config/xdesk/sessions/NAME.sh` 由 `xdesk setup` 找到桌面时生成一次，之后归用户，xdesk 不改写也不覆盖；用户自己的 `startx.sh`、官方 Termux:X11 都不读它，所以“在外屏上怎么起这个桌面”的定制（语言、X 输入法的变量和启动、ssh-agent、先跑的命令、换桌面）都写在这里。新生成的文件开头带注释，写明 XDesk 运行前已经设好的（`DISPLAY`、`PULSE_SERVER`/`PULSE_COOKIE`/`PULSE_SINK`，有 GPU 驱动时的 Mesa/zink 变量）、哪些不能动（`DISPLAY` 和声音那几个：动了画面或声音就不在 XDesk 上）、哪些随便加；最后一行保持 `exec dbus-run-session startxfce4` 原样时，XDesk 把它接到自己的长期 D-Bus 上（切换会话时已开的程序不关），改了就照用户写的执行。proot 的会话把这些变量写在 `env` 后面，文件里旧的 `DISPLAY=:1` 在运行时副本里换成当前显示号。设置 › 设备 › 某台 Linux 的“Linux 桌面”里点一个桌面，就在 Termux 里用 `$EDITOR`（没有就 nano，再没有 vi）打开它。
