# ReviewFault v0.4.0

v0.4.0 建立账号同步、平板布局和原生客户端。调度算法继续使用冻结的 v3 参数；ABI v4 只增加跨设备确定性历史重放。本次先发布 Android 与 Windows，HarmonyOS 6 产物待后续补发。

## 数据与同步

- schema/备份/C ABI 升级到 v4，并继续迁移 v1/v2/v3 数据与备份。
- 复习保存为不可变原始事实，调度状态成为可重建缓存；任何设备的 `due_at` 都不会作为同步事实覆盖其他设备。
- 本地修改与 outbox 同事务提交，服务端按 workspace 分配单调 `serverSeq` 并幂等接收。
- v4 备份包含内容、历史、媒体与正式 artifact，排除账号令牌、设备身份、同步游标、outbox、冲突和本地草稿。

## 账号与服务

- 邀请注册、验证邮箱、密码重置、15 分钟 access token 与按设备轮换的 30 天 refresh token。
- 内容 payload 使用 workspace 数据密钥 AES-256-GCM 加密，数据密钥由部署主密钥包裹。
- Docker Compose 提供 PostgreSQL 18、对象存储、同步服务和 Caddy TLS 入口。

## 客户端

- Android 删除旧 Activity 路径，录入、相机/相册和备份恢复统一进入 Compose。
- Compact 使用底栏，Medium/Expanded 使用侧边导航；窗口变化保留 ViewModel 中的复习状态。
- 新增 HarmonyOS 6 Stage/ArkUI/NAPI 工程，bundle 为 `cn.reviewfault.app`。

## 发布校验

同一 tag 在 core、backend、Android 和 Windows 通过后即可发布。当前附件包含签名 APK、Windows MSI/ZIP、同步镜像摘要与 SHA-256 清单；HarmonyOS HAP 可在后续手动发布中追加。

## 升级与安装

- Android：安装 `ReviewFault-android-v0.4.0.apk`；持久发布证书允许从 v0.3.2 覆盖升级并保留本地数据。
- Windows：优先使用 `ReviewFault-windows-v0.4.0-x64.msi`，也提供自包含便携 ZIP。
- HarmonyOS 6：后续补发签名 HAP；这是新的应用身份，不支持覆盖历史 Harmony 包。
- 同步服务：按 `sync-image-digest.txt` 固定镜像摘要部署，并在首次开放注册前配置 SMTP、对象存储、主密钥和离线备份。

数据库会按 v1→v2→v3→v4 顺序迁移。升级前仍建议导出备份；v4 备份有意不包含账号令牌、设备身份和同步队列，恢复后需重新登录。
