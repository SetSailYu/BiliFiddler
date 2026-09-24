# zzz_bilifiddler Magisk 模块

配合 BiliFiddler App 使用，提供**开机自动恢复**引流状态的能力。

- 本模块不含证书处理（证书提权请用现有的 zzz_promote_user_certs 模块）
- 开机时检查 /data/local/tmp/bilifiddler.enabled 标志：
  - 存在 -> 重新拉起 SNI 中继 + iptables REDIRECT 规则（恢复抓包态）
  - 不存在 -> 不做任何事
- App 的「开启」会写入该标志，「关闭」会删除该标志

**安装**: 压缩本目录为 zip，在 Magisk 中刷入，重启。
**注意**: 也可不刷本模块——App 本身即可完成全部开关操作，模块仅为开机自恢复。
