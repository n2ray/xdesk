# 外屏上的通知（NOTIFY.md）

状态：草稿 v0.1（2026-11-15），还没有写代码。优先级：0.6.0 之后的第一项。
决定（用户 2026-11-15）：三种呼出方式都要；横幅默认打开，用户在设置里改；第一版只做手机的通知。

## 1. 问题

在外屏上工作时看不到手机的通知：HyperOS 等系统不会把通知栏画到外接屏上，手机屏往往在一边、锁着或被触摸板占着。用户会错过消息、电话之外的提醒和验证码。

目标：像 macOS 右上角那样——新通知在外屏右上角弹一条**横幅**，从右上角拉出**通知中心**看全部、处理它们。所有内容上一样（安卓应用、Linux 桌面和窗口、Mac、启动台），和 Dock 同一套"推到边上"的逻辑。

## 2. 拿到通知

- `NotificationListenerService`（「通知使用权」，系统设置 › 特殊权限）。公开接口，不要 root、不要无线调试。第一次用时 XDesk 说明为什么要它、点一下跳到系统的开关页；没给权限时一切照旧，设置里有一行说明。
- 收到：`onNotificationPosted / Removed / RankingUpdate`，每条有包名、标题、正文、大图标/小图标、时间、分组（group key）、是否持续（ongoing）、是否静默、渠道重要性、`contentIntent`、动作按钮（`Notification.Action`，含 `RemoteInput`）。
- 不显示：持续通知（音乐、下载、前台服务、XDesk 自己的）、渠道重要性 MIN/LOW 的静默通知、`FLAG_GROUP_SUMMARY` 本身（只显示组里的消息）。
- 跟随手机的勿扰：`getCurrentInterruptionFilter()` 和排序里的 `matchesInterruptionFilter()`；勿扰时不弹横幅，只进通知中心。
- 一条通知在手机上被清掉（`onNotificationRemoved`），外屏上同时消失；在外屏上清掉 = `cancelNotification(key)`，手机上也没了。

## 3. 横幅

- 位置：外屏右上角，离边 16 dp（外壳尺寸 b·u，`Sizing.shell`），宽约 360 dp。多条时向下堆，最多 3 条，更多的直接进通知中心并在铃铛上计数。
- 内容：应用图标 + 应用名 + 时间，标题（粗），正文两行省略。同一个应用连续来的合成一条（"3 条新消息"）。
- 停留 5 秒后滑出；指针停在上面时不走，移开后 2 秒走。右上角的小 × 关掉横幅（通知留在通知中心）。
- 点横幅 = 打开它（§5）；横幅上的动作按钮最多 2 个；有快捷回复的显示「回复」，点开就地输入框（键盘在外屏，Enter 发送，Esc 取消）。
- 不抢输入：横幅出现时键鼠仍是内容的；只有指针移到横幅上、点它时才归横幅（和 Dock 一样，Linux/Mac 上由 XDesk 自己画箭头，安卓应用上用安卓的指针）。
- 声音和震动仍在手机上（系统自己发）；XDesk 不额外出声。

## 4. 通知中心

- 从外屏右边滑出的面板，宽约 400 dp，高度整屏，半透明深色背景（和启动台同一套外壳样式）。
- 顶部（用户 2026-11-15 加）：大字号的**时间**、**日期**（星期），手机的**电量**（图标 + 百分比，充电时带闪电，跟随 `ACTION_BATTERY_CHANGED`）；下面一行「勿扰」开关（切手机的勿扰，需要通知策略权限时引导一次）、「全部清除」。
- **低电量提醒**：手机电量降到 15% 和 5%（没在充电）时，各弹一条横幅「手机电量 15%」，和通知同一套横幅；充上电后清掉。设置里可关。
- 列表：按应用分组，组内按时间倒序；每条可展开看全文和大图；动作按钮、快捷回复、清除（× 或向右划/拖）。
- 点一条 = 打开它（§5）并关上面板。
- 关闭：Esc、再按一次快捷键、指针离开面板 1 秒、点面板外面。
- 键盘：↑↓ 选中，Enter 打开，Delete 清除，R 回复。

### 4.1 呼出（三种都要）

1. **推右上角**：指针在外屏右上角（右边缘最上面 48 dp 以内，或上边缘最右面 48 dp 以内）继续往外推。用 `EdgePush` 同一套判断（持续 `DWELL_MS`、推够 `PUSH`、中间停顿不超过 `GAP_MS`），方向换成向右或向上；整个桌面（XFCE、Mac）上用 `DESKTOP_*` 的长推，避开它们自己右上角的东西（XFCE 面板、Mac 的菜单栏和控制中心）。停在角上、一下甩过去都不算。
2. **铃铛**：外屏右上角常驻一个小铃铛（外壳尺寸 20 dp，半透明，有未读时变亮并显示数字），点它开关通知中心。设置里可以隐藏铃铛（隐藏后仍能推角、按快捷键）。全屏视频等内容时铃铛自动淡出，指针靠近才出现。
3. **快捷键 Ctrl+Alt+N**：在 `Ownership` / `XDeskActivity.physicalKey` 里和 Ctrl+Alt+M、Ctrl+Alt+Q 一起截（按下时动作，松开吞掉）。
4. 接收端：把手上加「通知」按钮（协议里一个新的 ANDROID 动作值，接收端要看 FEATURES 的新位才显示）——第二步再做。

## 5. 打开一条通知

- 目标：对应的应用**开在外屏上**，像在启动台点它一样（成为外屏上的内容，键鼠归它）。
- 做法（要实测，按顺序试）：
  1. `contentIntent.send(context, 0, null, null, null, null, options)`，`options = ActivityOptions.makeBasic().setLaunchDisplayId(外屏)`。Android 14+ 后台启动限制：用户刚点了 XDesk 自己的窗口，XDesk 在前台/有可见窗口，应当允许；`setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`。
  2. 应用开到了手机屏（PendingIntent 忽略了显示器）：用注入服务把它的任务挪到外屏（AppSession 已有把应用放到外屏的路径），再让外屏的内容切到它。
  3. 没有 `contentIntent`：打开这个应用的启动页（启动台的路径）。
- 打开前，外屏上原来的内容按平常的切换规则让位（成为「上一个」）。
- 手机锁着：PendingIntent 可能被要求先解锁。外屏显示「请在手机上解锁」，解锁后接着打开。

## 6. 隐私和设置

设置 › 外接屏 › 通知（新的一页）：
- **在外屏上显示通知**：开（默认）/ 关。关 = 不弹横幅、铃铛只计数，通知中心仍可手动打开。
- **横幅里的内容**：全部（默认）/ 只显示应用名（「微信 · 新消息」）/ 不弹横幅。
- **按应用**：列出最近发过通知的应用，每个可设「横幅 + 通知中心」/「只进通知中心」/「不显示」。
- **铃铛**：显示 / 隐藏。
- 投到 XDesk 接收端（投影机、会议室大屏）时可以单独设：默认「只显示应用名」。
- 内容只在内存里：不写盘、不记日志（日志只记包名和事件，不记标题正文）。

## 7. 实现

- `NotifyService extends NotificationListenerService`：收通知、过滤、维护列表（只在内存），通知 `NotifyCenter`。
- `NotifyCenter`：模型（列表、未读数、分组、设置），主线程；和 `Switchboard` 一样一个模型多个视图。
- `NotifyBanner`、`NotifyPanel`、`NotifyBell`：外屏上的悬浮窗（`TYPE_APPLICATION_OVERLAY`，和 `ExternalDock` 同一种窗口和尺寸规则）；无线显示器上一样。
- 指针：推角的检测和 Dock 共用 `EdgePush`（参数化方向）和 `DockPointer`（面板开着时 Linux/Mac 上指针归面板）。
- 快捷回复：`RemoteInput.addResultsToIntent` + `action.actionIntent.send(...)`。
- 权限缺失、XDesk 不在运行时：什么都不做（第一版不做常驻服务之外的唤醒）。

## 8. 以后（不在第一版）

- Linux 的通知：`xdesk` 在 X 会话的 D-Bus 上提供 `org.freedesktop.Notifications`，转给 XDesk 的通知中心；XFCE 自己的 xfce4-notifyd 那时让位。
- Mac 的通知：Sill 的协议里没有，要 Sill 支持。
- 接收端的「通知」按钮；Windows 设备接入后它的通知。
- 通知历史（今天收到过的，清掉后也能找回）。
