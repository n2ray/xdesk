# 实现来源与公开集成

以下为历史快照的来源。维护者已授权本地原创贡献按 GPL-3.0-only 发布，见 [LICENSING.md](LICENSING.md)。以下提交链接是归档时核对的上游版本，早期研究使用的确切版本仍待确认。

| 本仓库范围 | 已知来源与证据 |
|---|---|
| mprobe.py、phone_relay.py、phone-probe/DirectSession.java、AvcReceiver.java、make_profile.py 和 assets | 早期探针作者注明参考了 iShareScreen 的协议文档和实现，README 另列 pippin。Android 客户端从探针移植认证、协商及收包逻辑。能力模板来自本仓库探针，group 由 OpenSSL 的标准 MODP 4096 群生成。手机端 DeepSeek 会话的事后说明补充了 Python 函数来源，详见[早期探针来源补记](GPL-INTEGRATION-REVIEW.md#早期探针来源补记)。 |
| DirectSession.java 的 pointer/key，RemoteInput.java | 开发时核对了 iShareScreen 的鼠标封装与键值，以及 rootshell-vnc 的 AppleEncryptedInput.swift 键盘事件布局。MIT 参考实现的声明随本仓库保存。 |
| rev/NOTES.md、phone-probe/README.md | 记录本机协议研究和黑盒互通测试。Apple 程序二进制、Ghidra 数据库、反编译输出、桌面抓图、媒体流及签名密钥留在研究机。 |

## 核对过的上游

- iShareScreen 提供协议与实现，项目采用 AGPLv3。见[协议文档](https://github.com/renegadelink/iShareScreen/blob/9ab40d3a3151524f954cff9d6239d891197c1705/docs/apple_vnc_rfc.md)、[输入实现](https://github.com/renegadelink/iShareScreen/blob/9ab40d3a3151524f954cff9d6239d891197c1705/src/isharescreen/proxy/input.py)和[许可证](https://github.com/renegadelink/iShareScreen/blob/9ab40d3a3151524f954cff9d6239d891197c1705/LICENSE)。
- pippin 是早期 README 列出的参考项目，也采用 [AGPLv3](https://github.com/jonesy827/pippin/blob/610e01abcc4b320eab2971f3c25627ece4f5938d/LICENSE)。据手机端会话说明，当时只取了 main 的 README，没有阅读 Rust 源码。审查中引用的 Rust 文件用于事后比对。
- rootshell-vnc 的[键鼠消息参考实现](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBTransport/Session/AppleEncryptedInput.swift)采用 [MIT](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/LICENSE)。本地副本见 [LICENSES/rootshell-vnc-MIT.txt](LICENSES/rootshell-vnc-MIT.txt)。

## 与 XDesk 合并前

[四个协议模块的逐项审查](GPL-INTEGRATION-REVIEW.md)列出可移植函数、待替换的生成链、MIT 声明要求与固定提交证据。

按模块对照实现和文档，记录复用的代码、改写的代码、测试得到的协议事实，以及每段采用的授权。重点检查 SRP 消息、二进制能力模板、分片重组和输入封装。对保留的第三方实现附上对应许可证、版权声明、变更说明及发布时所需的对应源码。若目标是维持纯 GPLv3 发布，对来源不明的部分可向权利人取得兼容授权，或依据必要的协议事实独立实现并用真机测试验证。已有参考代码接触记录应一并保留。

## Android 重写版

此分支的 AppleLogin 按 rootshell-vnc 的类型 33 状态机、SRP 消息和 SHA-512 证明重新实现，并保留 M4 接受的身份字段布局和服务端选项回显。初版使用上游默认身份格式，在本机拿到挑战但证明未通过；调整两项后成功，未分别测量其影响。ControlWriter 按 MIT 描述生成带序号校验的 CBC 记录，SrtpStream 按标准实现 AES-256-CM、密钥派生和 HMAC-SHA1-80。既有的会话调度、加密切换顺序与输入封装继续使用。原 Python 探针仍是历史研究代码。公开集成时按文件选择，保留 MIT 声明。 [认证状态机](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBTransport/Crypto/MacAuthenticator.swift) · [记录格式](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBTransport/Crypto/AppleComCryptionChannel.swift) · [RFC 3711](https://www.rfc-editor.org/rfc/rfc3711) · [RFC 6188](https://www.rfc-editor.org/rfc/rfc6188)

资源生成入口改为 make_profile.py 与 CaptureOffers.m，五份资源的输入和实测范围见[真机结果](phone-probe/README.md#M4-原生协商模板与重写版直连复测)。键鼠和 H.264 组帧沿用原审查。 [控制模板字段](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBProtocol/Messages/MessageWriter.swift) · [随库 MIT 声明](LICENSES/rootshell-vnc-MIT.txt)

## XDesk 移植版

DirectSession 的控制初始化、1103 切换、UDP 端点维护和 0x1c 组包已按职责重写。0x1c 采用 Rootshell MIT 的单显示器头布局、两个方向的独立随机密钥和两个独立流描述。唤醒连接、端点准备和明文切换顺序来自本机互通历史。MediaOffer 将可读配置逐字段编码为 protobuf、zlib 和二进制 plist，在每次连接时生成 SSRC、时间及 Call ID。配置列出本机原生协商观察到的十种带宽模式、各流次序、版本值及 AVC 格式。互通字段的语义可另对照 Rootshell 的 MIT 文档。以固定 SSRC 在 Android 上运行编码器后，用独立 plist 解析器与旧成功样本比较，除运行时时间戳外两份 protobuf 的全部字段一致。APK 不含原生 offer 或历史 blob；配置文件和 Java 编码器即其可修改来源。[MIT 组包参考](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBTransport/Session/TransportSession.swift#L6091)

首帧恢复在已认证 RTP 上定位远端 SSRC，使用客户端发送方向的 SRTCP 密钥发出限频 FIR。参考 [MIT 恢复报文](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBTransport/Session/AppleMediaResiliencyFeedback.swift)及 [MIT SRTCP 实现](https://github.com/kitknox/rootshell-vnc/blob/9e95dc98032787db749c6a540c14a082791afd19/Sources/RFBTransport/Crypto/AppleSRTCPContext.swift)。
