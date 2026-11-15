# 游戏直通（GAMING.md）

状态：设想 v0.1（2026-11-15），还没有写代码，要先做下面 §5 的实验。来源：用户发现的用法——外屏开手机上的 GeForce NOW，蓝牙手柄玩游戏很流畅，无线投到投影机的接收端也没问题；但实体键盘和鼠标进不了 GeForce NOW。
相关：INPUT.md §7（键鼠归属）、§15（手柄原本不在范围内）、SHORTCUTS.md（Ctrl+Alt+G）。

## 1. 目标

手机 + 外屏（或无线投到接收端）当一台游戏主机：云游戏（GeForce NOW、Steam Link、Moonlight……）和本地游戏在外屏上全屏，**手柄、实体键盘、鼠标都能直接玩**，鼠标是游戏要的那种「捕获的相对移动」。

## 2. 现在为什么不行

- 外屏上是安卓应用时，键鼠「在外屏」的意思是：注入服务独占实体键鼠（EvdevReader 的 EVIOCGRAB），再用 `injectInputEvent` 把模拟的事件送进外屏（绝对坐标的悬停、点按、触摸）。
- GeForce NOW 的鼠标模式用 Android 的**指针捕获**（`requestPointerCapture`）：只收真实鼠标的相对移动（`SOURCE_MOUSE_RELATIVE`）。模拟注入的事件变不成捕获事件，所以「键鼠模式」在它里面完全无用。
- 触控模式能点、能按住拖动，是因为我们注入的触摸它认。
- 手柄从来没被抓，安卓直接送给前台应用，所以一直好用。

## 3. 做法：游戏直通（原生直通）

- **Ctrl+Alt+G** 打开 / 关上，只在外屏上是安卓应用时有效（G = game）。
- **打开时：**
  - 注入服务放开对实体键鼠的独占（EVIOCGRAB 交还），XDesk 不再转发它们；
  - 把外屏设成系统的顶层焦点显示器（`focusDisplay`），实体键盘的按键由 Android 直接送给外屏上的应用；
  - 鼠标由 Android 直接送到外屏，应用自己请求指针捕获，拿到原始相对移动；
  - XDesk 的光标藏起来，外屏角上短暂提示「游戏直通：Ctrl+Alt+G 退出」。
- **退出：** 注入服务继续**只读不抓**地读键盘（不再独占），只为听 Ctrl+Alt+G；游戏也会收到这组键，一般无害。退出后重新抓取，恢复原来的键鼠模式。
- **直通期间：** 推底边开 Dock、推角开通知、Ctrl+Alt+M/N 都不工作（键鼠不经过 XDesk）；只有 Ctrl+Alt+G 能退出。外屏内容换了（应用退出、被切走）就自动退出直通。
- **按应用自动：** 按包名的应用档案（`AppInputProfile`）加一项「默认直通」，先放 GeForce NOW（`com.nvidia.geforcenow`）、Steam Link（`com.valvesoftware.steamlink`）、Moonlight（`com.limelight`）；这些应用上外屏时自动进直通，设置里可关。
- **接收端：** 键鼠插在接收端（平板、电脑接收器）上时做不到：转发过来的只能是模拟注入，变不成捕获事件。直通要求键鼠连在手机上（蓝牙或 USB）。画面经无线投给投影机这条路不受影响（用户实测可以）。

## 4. 手柄

INPUT.md §15 写着「手柄不在范围内」，指的是 XDesk 不转发手柄。实际上手柄不经过 XDesk，Android 直接给前台应用，外屏上的游戏能用；这一条改成「手柄不经过 XDesk，由 Android 直接交给外屏上有焦点的应用」。

## 5. 先做的实验（HyperOS，15 Ultra）

1. 放开抓取、`focusDisplay(外屏)` 以后，实体键盘的键是不是进外屏上的 GeForce NOW？
2. 鼠标：Android 会不会把实体鼠标送到外屏（之前 HyperOS 小窗模式下外屏上看不到系统指针）；GeForce NOW 在外屏上请求指针捕获能不能成功、收到相对移动。
3. 只读不抓地读 evdev 时能不能可靠听到 Ctrl+Alt+G。
4. XDesk 手机上的窗口还在时，焦点会不会被抢回手机。
5. Steam Link、Moonlight 是不是同样。

实验 2 不行的话（HyperOS 不把鼠标送到外屏），退路是用 Android 的输入端口关联把鼠标绑到外屏（`InputManager` 的显示器端口关联，要 shell 权限）。XDesk 自己在外屏上抓指针再转发不是出路：转发出去的仍是模拟事件，变不成游戏要的捕获事件。
