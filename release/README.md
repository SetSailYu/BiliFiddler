# BiliFiddler — B站直连流量强制转发到 PC 端 Fiddler

一个把哔哩哔哩 App（tv.danmaku.bili）的直连流量（无视系统代理的那部分）强制送到
同一局域网内 PC 端 Fiddler 的解决方案，**手机端一个开关控制，PC 端零操作**。

## 原理
B站核心 API 用自带网络栈（魔改 OkHttp + 自带 BoringSSL）发 h2 直连，无视 WiFi 代理，
Fiddler 根本收不到。本方案在内核层（iptables）把 B站 uid 的出站 443/80 流量 REDIRECT
到本机一个极小的 SNI 中继（13KB C 程序），中继解析 TLS ClientHello 的 SNI 后，
以标准 CONNECT 协议转发给 PC 端 Fiddler，Fiddler 正常 MITM 解密。

   B站 App ──(直连 443/80)──> iptables REDIRECT ──> 本机 bfrelay(127.0.0.1:18987)
                                                                 │ CONNECT <sni>:443
                                                                 ▼
                                                        PC 端 Fiddler (10.0.0.x:8888)

## 组成
1. **BiliFiddler-v1.0.apk** — 图形界面，开关 + Fiddler 地址配置
   - 内置 arm64 + arm32 两套中继二进制，安装即用
   - 「开启」：写配置 → 解包中继 → 启动中继 → 挂 iptables 规则 → 写开机恢复标志
   - 「关闭」：摘规则 → 杀中继 → 删标志
   - 需要 Root（Magisk 授权，App 首次运行会弹授权框）
2. **zzz_bilifiddler.zip** — Magisk 模块（可选）
   - 开机自恢复：若上次抓包态未关闭，重启后自动重建中继 + 规则
   - 不刷也能用，App 本身即可完成全部操作

## PC 端要求（仅此而已，零脚本零任务）
- Fiddler 监听 8888（默认）
- Tools > Options > Connections > "Allow remote computers to connect" 勾选
- Tools > Options > HTTPS > "Decrypt HTTPS traffic" 勾选
- **证书**: 手机需已信任 Fiddler 根证书（本案例已由 zzz_promote_user_certs 模块提权到系统库）

## 使用
1. 装 APK，打开，首次会弹 Magisk 授权 → 允许
2. 填 PC 端 Fiddler 的局域网地址，如 192.168.1.100:8888
3. 打开「强制引流到 Fiddler」开关
4. PC 上正常开 Fiddler 即可看到 api.bilibili.com / app.bilibili.com / grpc.biliapi.net 等解密请求

## 注意
- Chase 7824 私有二进制协议（推送/心跳长连接）非 HTTP，任何代理都无法解析，本方案不覆盖
- UDP 443（QUIC）会被 REJECT 以强制回落 TCP h2，否则 Fiddler 看不到
- 若 App 在 B站风控/支付场景异常，拨掉开关即可恢复直连
- 卸载：App 内先「关闭并清理引流」，再卸载 App；Magisk 模块在 Magisk 里移除
