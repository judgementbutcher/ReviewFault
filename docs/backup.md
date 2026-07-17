# 备份与恢复协议 v1

`.reviewfault` 文件是 ZIP 容器，至少包含 `manifest.json` 与 `database.sqlite`；媒体按数据库中的相对路径放在 `media/`。清单结构由 `schema/backup-v1.schema.json` 固定。

导出前必须完成 SQLite checkpoint，随后计算每个文件的 SHA-256 与字节数。恢复时必须先解压到应用缓存中的随机目录，并依次验证：

1. ZIP 路径不能逃逸临时目录；
2. 格式版本、schema 版本与调度 ABI 兼容；
3. 清单列出的每个文件长度和 SHA-256 相符；
4. `PRAGMA integrity_check` 返回 `ok`；
5. `PRAGMA foreign_key_check` 没有结果；
6. 替换失败时回滚到原数据库与媒体。

Android、Windows 与鸿蒙均实现协议 v1。鸿蒙端使用官方 `RdbStore.backup/restore` 创建和恢复一致性快照，使用 `zlib.compressFiles/decompressFile` 处理 ZIP，并通过 `DocumentViewPicker` 与用户选择的位置交换文件；最低兼容 API 13，以获得系统压缩组件的路径穿越防护。
