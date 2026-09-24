# BiliFiddler

将哔哩哔哩 App（`tv.danmaku.bili`）的**直连流量**（无视系统代理、由自带网络栈发起的那部分）
强制转发到同一局域网内 PC 端 Fiddler 的工具。**手机端一个开关控制，PC 端零操作**。

## 背景 / 为什么需要它

B站 8.x 的核心 API（`api.bilibili.com`、`app.bilibili.com`、`grpc.biliapi.net` 等）由
自带网络栈（魔改 OkHttp + 自带 BoringSSL）发起 HTTP/2 **直连**，从设计上无视 WiFi 代理，
因此只配置 Fiddler 代理是抓不到的——证书修得再好，流量根本没进 Fiddler。

本工具在内核层（iptables）把 B站 uid 的出站 443/80 流量 REDIRECT 到本机一个极小的
SNI 中继（13KB 纯 C 程序），中继解析 TLS ClientHello 的 SNI 后，以标准 CONNECT 协议
转发给 PC 端 Fiddler，Fiddler 正常 MITM 解密。

```
B站 App ──直连 443/80──> iptables REDIRECT ──> 本机 bfrelay(127.0.0.1:18987)
                                                    │ CONNECT <sni>:443
                                                    ▼
                                            PC 端 Fiddler (10.0.0.x:8888)
```

## 目录结构

```
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

前置：JDK 17+，Android SDK（`local.properties` 里配置 `sdk.dir`），网络可访问 Google/Maven 仓库。

```bash
# Windows
gradlew.bat assembleRelease

# 产物: app/build/outputs/apk/release/app-release.apk
```

APK 用 `app/sign/bili.keystore`（密码 `bilifiddler`，别名 `bili`）签名。
如需更换签名，改 `app/build.gradle` 的 `signingConfigs.release`。

中继如需重新编译（改动了 `native/bfrelay.c`）：

```bash
# 需 Android NDK（本机用 r27），替换路径后：
aarch64-linux-android31-clang -O2 -o bfrelay64 bfrelay.c -llog
armv7a-linux-androideabi31-clang -O2 -o bfrelay32 bfrelay.c -llog
# 产物同步进 app/src/main/assets/native/ 与 magisk/zzz_bilifiddler/
```

## 使用

1. 手机装 `BiliFiddler-v1.0.apk`（或自构建），打开后 Magisk 弹授权 → 允许
2. 填入 PC 端 Fiddler 的局域网地址，如 `192.168.1.100:8888`
3. 打开「强制引流到 Fiddler」开关
4. PC 上正常开 Fiddler，即可看到 `api.bilibili.com/x/...` 等解密请求

关闭：拨掉开关，或点「关闭并清理引流」。开关状态与 Fiddler 地址本地持久化，
重启手机后如需自恢复，刷入 `magisk/zzz_bilifiddler.zip`。

## PC 端要求（仅此，零脚本零任务）

- Fiddler 监听 8888
- Tools > Options > Connections > 勾选 "Allow remote computers to connect"
- Tools > Options > HTTPS > 勾选 "Decrypt HTTPS traffic"
- 手机已信任 Fiddler 根证书（需提权到系统 CA 区，可用 Magisk 模块如 `zzz_promote_user_certs`）

## 工作原理（关键实现点）

- **中继**（`native/bfrelay.c`）：零依赖纯 C，单进程同时处理 TLS 与明文 HTTP。
  首字节 `0x16` = TLS 握手 → 解析 ClientHello 的 SNI 扩展 → 向 Fiddler 发 `CONNECT <sni>:443`；
  否则 = 明文 HTTP → 解析 Host 头改写为绝对 URI 转发。读配置 `/data/local/tmp/bilifiddler.conf`
  （`proxy <ip> <port>` / `listen <port>`）。
- **引流**（iptables）：`REDIRECT --to-ports 18987`（本机回环，故 PC 端无需中继），
  用 `-m owner --uid-owner <bili_uid>` 精确命中，uid 由 `dumpsys package` 动态解析，缺失时回退 10334。
- **QUIC 抑制**：UDP 443 用 `REJECT` 强制回落 TCP h2，否则 Fiddler 看不到 QUIC。
- **二进制落地**：中继经 App 私有目录 `cp` 到 `/data/local/tmp/bfrelay`，
  避开 shell heredoc 对 ELF NUL 字节的破坏（此前实测会截断）。

## 边界 / 注意

- **Chase 7824 私有二进制协议**（推送/心跳长连接）非 HTTP，任何代理都解析不了，本工具不覆盖；
  要看它需逆向 ChaseSDK 或 Frida hook 层 dump。
- B站对 VPN/root/代理有一定风控敏感度，支付/风控场景异常时拨掉开关即可恢复直连。
- 仅在已 Root（Magisk）且设备为自有/授权设备时使用。

## License

MIT — 见 LICENSE
