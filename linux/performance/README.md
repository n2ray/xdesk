# 内置 Ubuntu 桌面性能：Adreno 830 实测与改进提案

目标是让用户安装 XDesk、启动 Ubuntu 后，普通桌面应用、3D 图形和视频播放共同受益。建议先在已测的 Adreno 830 上验证包和驱动组合，再按设备能力扩大默认启用范围。无线调试仅用于采样。

## 已经跑通的组合

2026-11-23，小米 15 Ultra，Ubuntu 26.04 r2、xdesk-core 4、Chrome 158.0.8127.36、Mesa 27.0.0-devel（b84f02a451）、termuxva b67039566476。外屏前台同一个 Chrome 页面，WebGL Aquarium 绘图缓冲区固定 2560×1600、5000 条鱼，右下角播放本地 1080p60 视频。原型使用 ANGLE OpenGL / GLX、Zink / Turnip、KGSL 和 Android MediaCodec。

| 配置 | 采样 | 页面 fps | 外屏 fps | 视频丢帧 | CPU（Chrome / X / 解码桥） |
|---|---:|---:|---:|---:|---:|
| 发布默认 Freedreno + EGL，VP9 | 15 s | 9.5 | 9.6 | 758/903，83.9% | 140 / 12 / 16% |
| Zink 临时原型，VP9 | 90 s | 39.7 | 42.9 | 1949/5378，36.2% | 150 / 32 / 12% |
| Zink 临时原型，H.264 | 15 s | 39.8 | 42.9 | 321/898，35.7% | 153 / 33 / 12% |

外屏 fps 取目标 SurfaceView 经 SurfaceFlinger 实际呈现的去重时间戳；桌面可在鱼缸两帧之间更新。视频计数取同一窗口内 getVideoPlaybackQuality() 的增量。100% CPU 为一个核心。VP9 长测有 3863 个呈现样本，间隔中位数 16.7 ms、P95 33.3 ms，六个 15 秒窗口为 40.0–44.8 fps。Chrome 报告 VaapiVideoDecoder，Android 分别记录 c2.qti.vp9.decoder 和 c2.qti.avc.decoder。截取物理外屏视频区域，对照源片第 2、6、8 秒和连续播放的变色色标，确认纹理在更新。[采样计数](adreno830.csv)

## 故障边界与修复位置

1. **隔离视频驱动的符号。** 在同一份极简 VA 加载器里只切换 dlopen 标志，RTLD_LOCAL 在 15 秒内得到 661 个外屏帧（44.2 fps），RTLD_GLOBAL 得到 0 个，后者页面仍报 46.5 fps。直接按发布方式加载 VA 驱动的 90 秒对照也只有 1 个新呈现时间戳。可以在 Mesa fork 中收窄导出符号，或在 Chrome 专用入口里局部加载视频驱动；发布前验证具体冲突符号、驱动卸载及 GPU 进程重启。[视频驱动 fork](https://github.com/n2ray/mesa-for-android-container/tree/xdesk)
2. **打通解码表面到窗口纹理的生命周期。** 同步抽读的 NV12 和颜色转换后的 BGRA 都有有效像素。NV12 两平面 pitch 均为 1920，UV 偏移为 2088960。Chrome 通过旧 DRI3 PixmapFromBuffer 提交单平面 32 位视频 pixmap；把隐式 modifier 转成服务器可识别的显式请求后，单靠该改动仍显示黑块。首次上传 BGRA 只能显示旧帧，复用纹理时刷新才与源片同步。生产实现应按 fd、格式、各平面偏移和 pitch 传递真实布局，让生产者完成写入后消费者再采样，消费者释放后才回收缓冲区。优先验证共享 DMA-BUF / EGLImage；无法导入时保留已验证的同步上传回退。原型只匹配 1920×1080、pitch 7680 的 BGRA，需补齐动态尺寸、多个 GL 上下文、seek 和销毁。[X 服务导入实现](../../lorie/src/main/cpp/lorie/tod-glamor.c)
3. **优化整桌面的 X11 发布。** 单跑鱼缸的独立 MISURA 采样中，Chrome 47.6 fps、X 发布 44.6 fps、外屏 44.3 fps。每次更新的 DMA-BUF 同步开始平均 2.83 ms，memcpy 1.68 ms，同步结束及交换 1.98 ms，等待上一份复制缓冲区约 0.03 ms。根画面 blit 加 glFinish 平均 3.94 ms，采样次数与窗口更新不同，不能直接相加预测帧率。建议在 Termux:X11 fork 先按实际格式验证可直接导入的缓冲区，再用显式 fence 替换已验证可移除的同步点，分别复测窗口更新和根画面发布。拖动窗口与全屏视频应各测一轮。[Present 和 DRI3 入口](../../lorie/src/main/cpp/lorie/InitOutput.c)

## 从构建交付到默认启用

| 位置 | 建议的独立改动 | 验收 |
|---|---|---|
| Mesa 和视频驱动 | 固定桌面 Mesa、termuxva、兼容库的源码版本和产物校验值。先在同一 Chrome / ANGLE 配置下比较驱动版本，再决定是否替换现用 lfdevs 桌面构建。 | 核对实际 renderer；测试多个窗口、不同视频尺寸、颜色和纹理复用；不支持的格式可回退。 |
| xdesk-core 与会话启动 | 由包提供版本化的设备配置和 Chrome 专用入口。会话脚本当前会重写 /etc/xdesk/chrome.sh，应让它读取包的配置。按 KGSL、解码桥和缓冲区导入能力选择路径，允许回到现用配置。 | 全新镜像和已有 r2 的 apt 升级选择同一组合；覆盖离线启动、Mesa 重装触发器和恢复旧配置。 |
| Burrow 与 Ubuntu 用户态 | 在相同图形路径下单独采样应用冷启动、包解压、小文件 I/O、并行编译的耗时和 CPU；与同机原生 ARM64 用户态对照时记录文件系统、缓存、温控和编译参数。 | 建立可重复的基线后再决定是否改 Burrow；本次鱼缸数据无法归因其通用 CPU 或 I/O 成本。 |
| Termux:X11 与 Android 宿主 | 对协商失败、实际复制字节、等待和发布分别计数；让旧 APK 能继续使用兼容路径。共享缓冲区协议变化需 Ubuntu 包与 APK 协商版本。 | 区分应用回调、X 发布及物理外屏三组帧率；反复插拔外屏、切换分辨率、休眠恢复和重启 GPU 进程。 |

现有安装入口可直接承接这些工作。[镜像构建](../build-rootfs.sh) · [驱动安装与触发器入口](../packages/xdesk-core/usr/lib/xdesk/xdesk-gpu) · [会话启动](../../lorie-app/src/main/assets/linux/xdesk-session) · [包升级测试](../test-xdesk-core.sh) · [镜像 CI](../../.github/workflows/linux-rootfs.yml) · [apt CI](../../.github/workflows/linux-apt.yml)

## 发布前怎样复测

- **设备与参照。** 固定外屏刷新率、绘图缓冲区、鱼缸画质、视频编码和温控条件。Android Chrome、Termux Chromium、内置 Ubuntu 各记录版本、实际 ANGLE 后端和 renderer。Termux 的 Mesa 26.3.1 在同机单跑 2560×1600 鱼缸时页面约 44.9 fps（5000 条）和 19.6 fps（30000 条）；这个参照没有叠加视频。
- **分离负载。** 分别跑鱼缸、视频、两者同屏。鱼缸覆盖 1280×800、2560×1600 与 5000、30000 条鱼。视频覆盖 VP9、H.264 的单独播放和混合负载；再验证设备宣称支持的 HEVC、AV1。每组同时记录解码组件、浏览器丢帧、外屏呈现间隔和各进程 CPU。用已知时间色标或对应源帧检查像素，避免把黑屏或静止画面算作成功。
- **完整桌面。** 在稳定负载和热机状态复测滚动、窗口拖动与缩放、视频 seek、全屏切换、应用启动和文件操作。保留同机发布版基线与回退结果。单设备原型中的 36% 视频丢帧表明，混合负载仍需优化，暂不宜作为所有设备的默认值。
