# 技术架构

## 为什么保留原生 UI

Android、Windows 与 HarmonyOS 在输入能力上差异明显：移动端需要系统选择器和触控手势，Windows 需要剪贴板、拖放、窗口与键盘快捷键。界面和系统集成分别原生实现，避免最低公分母式体验。

调度算法必须跨端完全一致，因此放在小型 C++ 核心中。核心不负责 UI、数据库、联网或本地日期，只接收 UTC 秒和纯数据并返回结果。所有可变业务数据由客户端写入同一版本的数据契约。

```text
Compose / WinUI / ArkUI
            │
应用服务（队列、录题、作答、备份、同步）
            │
SQLite/Rdb 仓储 ─── 媒体文件仓储 ─── sync API
            │
reviewfault_core（C ABI：UTC 输入 → 调度结果）
```

## 模块边界

- `core/`：调度、校验、版本化 C ABI；不得依赖平台 SDK。
- `apps/android/`：Kotlin UI，使用 JNI 封装核心。
- `apps/windows/`：C#/WinUI UI，使用 P/Invoke 封装核心 DLL。
- `apps/harmony/`：ArkTS/ArkUI Stage UI，使用 NAPI 封装核心共享库。
- `services/sync/`：邀请制账号、加密 workspace 同步和媒体对象服务。
- `schema/`：只追加的 SQLite 迁移与版本化备份 manifest schema。
- `fixtures/`：各语言绑定必须通过的版本化黄金样例。

## 时间与一致性

数据库中保存 UTC Unix 秒；时区只参与“今天”的展示和日界线计算，并随 v3 事件记录当时偏移。调度器不读取系统时钟，调用方显式传入 `reviewed_at`，因此测试可复现。服务层必须在同一事务中写入类型化调度状态、数学 attempt 和不可变事件。

跨端浮点计算统一使用 IEEE-754 `double`，最终到期时间量化到整数秒。C ABI 中只出现固定宽度整数、`double` 和 POD 结构体，并带 ABI 版本。

## 本地优先与同步

每个业务实体使用 UUIDv7；编辑记录携带 `updated_at` 和设备 ID。媒体以内容哈希命名并按哈希去重，
随软删除内容保留。离线读写始终是主路径；v0.4 将复习保存为不可变 `review_action_v4` 事实，
调度结果只作为可重建缓存。
本地业务修改与 `sync_outbox` 同事务提交；服务端按 workspace 分配单调游标并保存字段级
冲突。客户端 pull 只投影远端事实，不把它们重新写入 outbox。账号令牌保存在平台安全存储，
不进入 SQLite、备份或同步 payload。

## 隐私

题面、答案和学习记录默认仅保存在设备上。同步必须由用户登录后明确启用；服务端内容和媒体使用 workspace 数据密钥加密，账号令牌只进入平台安全存储。备份导出会净化设备与账号状态，并用 manifest 哈希校验完整性。
