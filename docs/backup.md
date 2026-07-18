# 备份与恢复协议 v4

`.reviewfault` 是 ZIP 容器，至少包含 `manifest.json` 与 `database.sqlite`，媒体位于
`media/`。v4 清单由 `schema/backup-v4.schema.json` 固定，声明应用 0.4.x、schema 4、
调度 ABI 4，并为每个负载保存 SHA-256 和字节数。清单还固定声明已排除
`local_device`、`sync_cursor`、`sync_revision`、`sync_outbox`、`sync_conflict` 与
`local_ink_draft`；账号令牌位于平台安全存储，本来就不进入备份。

恢复顺序为：限制解压总量与文件数、安全路径解压、拒绝重复或未声明文件、版本组合校验、逐文件长度/哈希校验、SQLite
`integrity_check`、`foreign_key_check`，最后以可回滚事务替换本地数据。v0.4 接受：

- manifest/schema/ABI `1/1/1`，恢复后顺序执行 `002_v0_2.sql`、`003_v0_3.sql`、`004_v0_4.sql`；
- manifest/schema/ABI `2/2/2`，恢复后执行 `003_v0_3.sql`、`004_v0_4.sql`；
- manifest/schema/ABI `3/3/3`，恢复后执行 `004_v0_4.sql`；
- manifest/schema/ABI `4/4/4`，净化后直接恢复。

其他组合和损坏文件在替换本地数据库之前拒绝。媒体包括已软删除内容的文件。学习设置
随 SQLite 恢复；主题、提醒时间和通知权限不得被恢复覆盖。恢复完成后会生成新的本机设备身份，
并要求用户重新登录同步账号。旧客户端必须依据未知 manifest 版本安全拒绝 v4，而不是尝试打开
或覆盖本地数据。
