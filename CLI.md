# xdesk 命令（CLI.md）

状态：方案，2026-11-16，随 0.6.0 测试版做。
相关：DEVICES.md（环境 = 设备）、INPUT.md §5.2（输入法）、CURSOR.md（光标主题）。

`xdesk`（仓库 `xdesk/xdesk`，装在 `$PREFIX/bin/xdesk`）是 XDesk 和 Termux 之间的桥：XDesk 只当显示器，Termux 原生、proot、chroot 里的桌面和程序都由它在对应的环境里拉起来。X 服务器的代码在 App 里：loader 每次启动 X 时加载已装的 XDesk，所以 App 更新后 X 重启一次就是新代码；会落后的只有 `xdesk` 这个脚本。

这一轮做三件事：版本对齐（§1）、给用户和给 App 的接口分开（§2）、最大兼容（§3）。

## 1. 版本对齐

- **版本号写进脚本。** 脚本里 `XDESK_CLI_VERSION=dev`；CI 发版时把 `out/xdesk` 里这一行改成发版的版本号（如 `0.6.0-beta.5`）。自己从仓库拷的脚本是 `dev`，App 不对 `dev` 提示。
- **`xdesk version`**（也认 `--version`、`-V`）：打印 `xdesk 0.6.0-beta.5`。`xdesk status` 多一行 `xdesk: 0.6.0-beta.5`。
- **`xdesk update [VERSION]`**：重新运行安装脚本，只更新命令（`XDESK_CLI_ONLY=1`：不下载 APK、不问 allow-external-apps、不跑 setup 以外的东西；loader 也换，便宜且无害）。不给版本就装最新的 Release（含测试版），给了就装 `vVERSION`。没网、校验不过时什么都不换，退出码非 0。
- **App 这边：**
  - 读到的命令版本和 App 的 `versionName` 不同（且不是 `dev`），或者命令太旧报不出版本：Termux 那一栏显示「xdesk 命令是 X，App 是 Y」和一个「更新命令」按钮。
  - 按钮：命令认 `update` 就经 RUN_COMMAND 后台跑 `xdesk update Y`，完了重新读状态；不认（太旧）就走现在的「复制安装命令、打开 Termux」，安装命令带 `XDESK_VERSION=vY`。
  - **X 服务器是 App 更新前启动的**（状态里 X 的启动时间早于 App 的 `lastUpdateTime`）：提示「X 服务器还在跑更新前的代码，停掉桌面再开一次生效」。只提示，不替用户重启。

## 2. 两套接口

**给用户的**（`xdesk`、`xdesk help` 只列这些）：

| 命令 | 作用 |
|---|---|
| `start [NAME]`、`stop`、`switch NAME`、`status` | 桌面会话 |
| `run CMD…`、`x` | 任意程序；只起 X 和声音 |
| `apps`、`launch ENV/ID` | 单个程序 |
| `setup`、`list`、`add`、`rm`、`use` | 找环境、管会话 |
| `ime …` | X 这边的输入法和键盘布局 |
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
- 字符串按 JSON 转义（引号、反斜杠、控制字符）。
- App 发的命令是 `xdesk app state 2>/dev/null || { 老的三段命令; }`：旧脚本不认 `app`，退出码非 0，同一次 RUN_COMMAND 里退回老格式，App 两种都会解析。输出以 `{` 开头就按 JSON 读。
- 以后 App 要的新东西加进 JSON，不再往 `status` 的文字里加行。

## 3. 最大兼容

### 3.1 环境不必有桌面
现在 `setup` 只在 proot 容器里找到 XFCE、LXQt、MATE 时才记下它，只装了 Firefox 的容器根本不出现。改成：
- 每个 proot-distro 容器都是一个环境（`proot-<distro>`），有没有桌面都在 `xdesk envs`、`xdesk apps` 里，能单独开程序。没有桌面的环境，单开程序时用 Termux 的 xfwm4 管窗口（已有的退路）；Termux 也没有 xfwm4 就不带窗口管理器开，记日志，`doctor` 提示装 `xfwm4`。
- 记在 `~/.config/xdesk/envs/NAME.root`（根目录）和 `NAME.launch`（进入环境运行一条命令的前缀），和 `xdesk add --root/--launch` 写的一样；会话（`sessions/*.sh`）是环境里的桌面，可以没有。
- `xdesk add NAME --root DIR --launch 'PREFIX'` 不给命令（没有 `'CMD'`）时只登记环境。

### 3.2 认更多桌面
Termux 和容器里都找：XFCE、LXQt、MATE，加上 LXDE（`startlxde`）、KDE Plasma X11（`startplasma-x11`）、Openbox（`openbox-session`）、i3（`i3`）。

### 3.3 `xdesk doctor`
一条命令查出常见问题，每项一行 `✓`/`!`/`✗` 加一句怎么修，有 `✗` 时退出码 1：
- 命令版本、loader 在不在；
- `allow-external-apps`（没开 App 就用不了 Termux）；
- 必要的命令（`need_tools` 那些）、pulseaudio；
- X：在不在跑、socket 在不在、是不是 App 更新前启动的（看不到 App 的时间就只报启动时间）；
- 窗口管理器：Termux 里有没有 xfwm4；
- 中文字体（`fc-list :lang=zh`，没有就提示 `pkg install noto-fonts-cjk` 一类）；
- 光标主题（已有的 `cursor_installed`）；
- 每个环境：根目录在不在、进入命令能不能用（`proot-distro` 装没装）、有几个程序、有几个桌面；
- 输入法 fcitx5（只报状态，不算错）。

### 3.4 认不出的环境
chroot 要 root 才看得到，Andronix、AnLinux 和自己写的 proot 脚本各不相同，不自动登记。`setup` 结尾说一句：「别的 proot 或 chroot：xdesk add NAME --root DIR --launch '进入命令'」，`doctor` 同样提示。

## 4. 测试
`xdesk/test/cli-test.sh`：假的 `$HOME`、`$PREFIX`、proot-distro 容器（只建目录和 `.desktop` 文件），不起 X，查 `setup`、`envs`、`apps`、`app state`（用 jq 校验 JSON）、`version`、`help`、`doctor` 的输出。
