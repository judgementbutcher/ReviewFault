# 备份与恢复协议 v2

`.reviewfault` 是 ZIP 容器，至少包含 `manifest.json` 与 `database.sqlite`，媒体位于
`media/`。v2 清单由 `schema/backup-v2.schema.json` 固定，声明应用 0.2.x、schema 2、
调度 ABI 2，并为每个负载保存 SHA-256 和字节数。

恢复顺序为：限制解压总量与文件数、安全路径解压、拒绝重复或未声明文件、版本组合校验、逐文件长度/哈希校验、SQLite
`integrity_check`、`foreign_key_check`，最后以可回滚事务替换本地数据。v0.2 接受：

- manifest/schema/ABI `1/1/1`，恢复后立即执行 `002_v0_2.sql`；
- manifest/schema/ABI `2/2/2`，直接恢复。

其他组合和损坏文件在替换本地数据库之前拒绝。媒体包括已软删除内容的文件。学习设置
随 SQLite 恢复；主题、提醒时间和通知权限不得被恢复覆盖。v0.1 必须依据未知 manifest
版本安全拒绝 v2，而不是尝试打开或覆盖本地数据。
