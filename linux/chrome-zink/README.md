# Chrome 图形与视频配置

xdesk-core 5 在内置 Ubuntu 的 Google Chrome 启动器里选择图形路径。已验证的组合为 Adreno 830、KGSL、可用的 termux-va 服务，以及 xdesk-gpu 安装的 b67039566476 视频驱动。符合条件的桌面用户从应用菜单或 google-chrome-stable 启动，即可使用 Zink / Turnip 和 MediaCodec。其他设备沿用会话原有配置。同一入口适用于新镜像和从 v4 升级的系统。

退出该用户已有的 Chrome 进程后，可运行以下命令切回会话原先的 Freedreno 配置，便于比较或排查问题。

```sh
XDESK_CHROME_ZINK=0 google-chrome-stable
```

## 一帧如何到达屏幕

- **驱动隔离。** libva 装载一个小型入口库；入口使用 RTLD_LOCAL 打开指定版本的 termuxva，委托实际解码。Zink 和视频驱动由此可以在 Chrome GPU 进程中使用各自的 Mesa 符号。
- **识别缓冲区。** 在 VA 导入或导出表面时记录线性、单平面、零偏移的 AR24/XR24，以及其 fd 对应的设备号、inode、尺寸和行距。Chromium 提交匹配的旧 DRI3 pixmap 请求时，兼容库保留隐式布局，转换为 XDesk X 服务能识别的请求，并持有独立的 fd 引用。多平面 NV12 由视频驱动负责颜色转换。
- **纹理复用。** GLX 绑定视频 pixmap 时建立纹理，VA 每次提交新画面都会更新该缓冲区的写入计数。再次绑定纹理时，计数变化才在 DMA-BUF 读同步区间内上传 BGRA。尚未观察到写入时，每次绑定均刷新。解绑、删除纹理、销毁 pixmap、VA 表面或上下文时清除对应记录。当前实现有一次 CPU 纹理上传，混合负载仍可能丢视频帧。

构建包时在 arm64 Ubuntu 环境编译私有库。Chrome 启动器只为该次进程及其子进程设置库路径；系统的 libGL 和已安装的 Mesa 文件仍由原有包管理。镜像构建、apt 构建与设备安装使用同一套产物。

## 真机验收（2026-11-23）

小米 15 Ultra，Adreno 830，Ubuntu 26.04 r2，Chrome 158.0.8127.36，Mesa 27.0.0-devel。将 CI 产出的 arm64 deb 从 v4 升级安装，以 xdesk 用户运行 /usr/bin/google-chrome-stable，每组使用独立测试资料目录。外屏前台同一页面固定 2560×1600 WebGL 绘图缓冲区、5000 条鱼，右下角循环播放本地 1920×1080、60 fps 的十秒视频。

| 配置和片源 | 采样秒数 | 页面 fps | 外屏实显 fps | 视频丢帧 | CPU（Chrome 合计 / X / 解码桥） |
|---|---:|---:|---:|---:|---:|
| v4，VP9 | 90.2 | 8.9 | 9.0 | 4517/5328（84.8%） | 139% / 11% / 15% |
| v5，VP9 | 90.1 | 41.8 | 46.3 | 1778/5376（33.1%） | 147% / 32% / 11% |
| v5，H.264 | 15.1 | 41.2 | 46.5 | 311/902（34.5%） | 150% / 34% / 11% |

外屏读数来自 SurfaceFlinger 目标 SurfaceView 的去重呈现时间戳；视频丢帧取同一窗口内 getVideoPlaybackQuality() 的增量。CPU 的 100% 为一个核心。v5 实际 renderer 为 ANGLE OpenGL / Zink / Turnip，Chrome 报告 VaapiVideoDecoder。Android 侧确认使用高通 VP9 和 AVC 解码组件。将外屏视频区域与独立解码的第 2、6、8 秒源帧比较，验证 seek 后像素随视频更新。

测试产物 SHA-256 为 `e40754407582bb741704e73f48b53c2f76000c533b0465503eee49ca0435780d`。[arm64 包构建及新旧系统安装测试](https://github.com/n2ray/xdesk/actions/runs/45213362466) · [完整镜像构建](https://github.com/n2ray/xdesk/actions/runs/45213483157)
