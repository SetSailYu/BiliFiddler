# BiliFiddler

[![GitHub release](https://img.shields.io/github/v/release/SetSailYu/BiliFiddler?style=flat-square)](https://github.com/SetSailYu/BiliFiddler/releases)
[![License](https://img.shields.io/github/license/SetSailYu/BiliFiddler?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android)](https://github.com/SetSailYu/BiliFiddler/releases)

将哔哩哔哩 App（`tv.danmaku.bili`）的**直连流量**——即无视系统代理、由自带网络栈发起的那部分——
强制转发到同一局域网内 PC 端 Fiddler 的工具。**手机端一个开关控制，PC 端零操作。**

## 特性

- 🔌 **内核层引流**：基于 iptables 精确命中 B站 uid 的出站 443/80 流量，对 App 本身完全透明。
- 🪶 **超轻量中继**：13KB 纯 C 的 SNI 中继，零依赖，单进程同时处理 TLS 与明文 HTTP。
- 🎛️ **一个开关搞定**：App 内开关 + Fiddler 地址配置，状态本地持久化。
- 🔁 **开机自恢复**：可选 Magisk 模块，重启后自动重建引流规则。
- 📦 **开箱即用**：内置 arm64 + arm32 两套中继二进制，无需 NDK 即可构建。

## 背景 / 为什么需要它

B站 8.x 的核心 API（`api.bilibili.com`、`app.bilibili.com`、`grpc.biliapi.net` 等）由自带网络栈
（魔改 OkHttp + 自带 BoringSSL）发起 HTTP/2 **直连**，从设计上无视 WiFi 代理，因此只配置 Fiddler 代理
是抓不到的——证书修得再好，流量根本没进 Fiddler。

本工具在内核层（iptables）把 B站 uid 的出站 443/80 流量 REDIRECT 到本机一个极小的 SNI 中继，中继解析
TLS ClientHello 的 SNI 后，以标准 CONNECT 协议转发给 PC 端 Fiddler，Fiddler 正常 MITM 解密。

## 工作原理

```text
B站 App ──直连 443/80──> iptables REDIRECT ──> 本机 bfrelay (127.0.0.1:18987)
                                                    │  CONNECT <sni>:443
                                                    ▼
                                            PC 端 Fiddler (192.168.1.x:8888)
```

1. **引流**：iptables `REDIRECT --to-ports 18987`，用 `-m owner --uid-owner <bili_uid>` 精确命中
   B站 uid 的出站 TCP 80/443；uid 由 `dumpsys package` 动态解析，缺失时回退 `10334`。
2. **中继**：本机 `bfrelay` 监听 `127.0.0.1:18987`，解析 ClientHello 的 SNI，向上游 Fiddler 发送
   `CONNECT <sni>:443`，收到 `200` 后回放 ClientHello 并双向透传。
3. **QUIC 抑制**：UDP 443 用 `REJECT` 强制回落 TCP h2，否则 Fiddler 看不到 QUIC 流量。

## 快速开始

### 下载

从 [Releases](https://github.com/SetSailYu/BiliFiddler/releases) 下载最新版本：

| 文件 | 说明 |
|---|---|
| [BiliFiddler-v1.0.apk](https://github.com/SetSailYu/BiliFiddler/releases/download/v1.0/BiliFiddler-v1.0.apk) | Android App，内置中继二进制，安装即用 |
| [zzz_bilifiddler.zip](https://github.com/SetSailYu/BiliFiddler/releases/download/v1.0/zzz_bilifiddler.zip) | Magisk 模块（可选，开机自恢复） |

### 前置条件

- 手机已 **Root**（Magisk），首次运行会弹授权框。
- 手机与 PC 处于**同一局域网**。
- PC 端已按下方「PC 端准备」配置好 Fiddler。

### 使用步骤

1. 安装 `BiliFiddler-v1.0.apk`，打开后允许 Magisk 授权。
2. 填入 PC 端 Fiddler 的局域网地址，如 `192.168.1.100:8888`。
3. 打开「强制引流到 Fiddler」开关。
4. 打开 B站 App，即可在 Fiddler 中看到 `api.bilibili.com/x/...` 等解密请求。

> **关闭**：拨掉开关，或点「关闭并清理引流」。开关状态与 Fiddler 地址本地持久化；
> 重启手机后如需自恢复，刷入 `zzz_bilifiddler.zip`。

## PC 端准备（仅此，零脚本零任务）

- Fiddler 监听 **8888**
- Tools › Options › Connections › 勾选 **"Allow remote computers to connect"**
- Tools › Options › HTTPS › 勾选 **"Decrypt HTTPS traffic"**
- 手机已信任 Fiddler 根证书（需提权到系统 CA 区，可用 Magisk 模块如 `zzz_promote_user_certs`）

## 目录结构

```text
├── app/                     # Android App（GUI 开关 + 地址配置）
│   ├── src/main/java/.../MainActivity.java
│   ├── src/main/assets/native/bfrelay64 / bfrelay32   # 内置中继二进制
│   └── sign/bili.keystore   # 开发签名密钥
├── native/                  # 中继 C 源码 + 已编译产物
│   ├── bfrelay.c
│   └── out/bfrelay64 / bfrelay32
├── magisk/zzz_bilifiddler/  # Magisk 模块（可选，开机自恢复）
├── release/                 # 已打包交付物（APK + 模块 zip）
├── gradlew(.bat)            # Gradle wrapper，可直接构建
└── settings.gradle / build.gradle
```

## 构建

前置：JDK 17+，Android SDK（在 `local.properties` 中配置 `sdk.dir`），网络可访问 Google/Maven 仓库。

```bash
# Windows
gradlew.bat assembleRelease

# 产物: app/build/outputs/apk/release/app-release.apk
```

APK 用 `app/sign/bili.keystore`（密码 `bilifiddler`，别名 `bili`）签名。如需更换签名，改
`app/build.gradle` 的 `signingConfigs.release`。

中继如需重新编译（改动了 `native/bfrelay.c`）：

```bash
# 需 Android NDK（本机用 r27），替换路径后：
aarch64-linux-android31-clang -O2 -o bfrelay64 bfrelay.c -llog
armv7a-linux-androideabi31-clang -O2 -o bfrelay32 bfrelay.c -llog
# 产物同步进 app/src/main/assets/native/ 与 magisk/zzz_bilifiddler/
```

## 常见问题（FAQ）

**抓不到包怎么办？**
按顺序排查：① 确认开关状态为「● 引流已开启」；② PC 防火墙放行 8888；③ Fiddler 已勾选
"Allow remote computers to connect" 与 "Decrypt HTTPS traffic"；④ 手机已信任 Fiddler 根证书；
⑤ App 内点「刷新状态」确认 `rules=2 relay=1`。

**为什么强制 REJECT UDP 443？**
B站 8.x 优先尝试 QUIC（UDP 443），若直接放行，Fiddler 只能看到 UDP 而非可解密的 TLS。
REJECT 后客户端会回落到 TCP h2，流量才能进 Fiddler。

**Chase 7824 长连接抓不到？**
推送/心跳长连接是私有二进制协议，非 HTTP，任何代理都无法解析，本工具不覆盖；要看它需逆向
ChaseSDK 或 Frida hook 层 dump。

**支付/风控场景出现异常？**
B站对 root/VPN/代理有一定风控敏感度，拨掉开关即可恢复直连。

## 边界与注意事项

- 仅在已 Root（Magisk）且设备为**自有/授权设备**时使用。
- 卸载前先在 App 内「关闭并清理引流」，再卸载 App；Magisk 模块在 Magisk 中移除。
- 本工具仅用于学习、调试与授权范围内的安全测试，请遵守法律法规与平台服务条款。

## License

MIT — 见 [LICENSE](LICENSE)
