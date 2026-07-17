# 需求验证矩阵

| 需求 | 实现证据 | 当前验证 |
|---|---|---|
| 鸿蒙、Android、Windows 原生 UI | `apps/harmony` ArkUI、`apps/android` Android View/Kotlin、`apps/windows` 纯 C# WinUI 3 | Android 调试 APK 已构建并通过 Lint/签名检查；WinUI 托管 Compile 目标 0 警告通过；鸿蒙 HAP 与 Windows 最终包仍需对应 OS runner |
| 三端同一复习时间 | C++20 调度内核、C ABI v1、JNI/NAPI/P/Invoke、黄金样例 | 单元测试与真实 `.so` 动态加载通过；Android JNI 已双 ABI 编译且启动含黄金自检，Harmony NAPI 用官方头文件通过 `-Werror` 语法编译 |
| 自动间隔复习 | D-S-R 模型、四档评分、目标保留率、学习/再学习状态 | 新卡、逾期、失败、评分顺序、保留率、无效输入均已测试 |
| 数学高效录题 | 三端一次选择 1–5 张图片；内容哈希去重、题面先存后补 | Android Kotlin/双 ABI NDK 编译通过，Windows 仓储多图集成测试通过；移动端选择器真机交互待验证 |
| 数学错题本 | 搜索浏览、相机/相册/文件多图录入、解答、错误步骤、关键提示、七类错因、每次重做 attempt | schema 约束与事务已测试；Android UI 编译/Lint 通过，Windows 仓储集成通过，鸿蒙 UI 待 SDK 编译 |
| 408 合适记忆形式 | 问答、隐藏答案填空、逐层提示、结构化枚举、对比；schema 另支持图示遮挡 | 模板领域校验、Windows 结构化持久化测试和 Android 编译通过；图示遮挡编辑器不在首版 UI |
| 今日学习队列 | 逾期/到期/新内容排序、408 优先、短复习优先、新内容日上限 20 | 共享领域测试通过；三端查询执行日上限 |
| 离线保存、编辑、搜索 | SQLite schema v1、三端本地仓储与题库 UI | 内存 SQLite 迁移/约束测试通过；平台数据库待设备测试 |
| 历史不可篡改 | `review_log` UPDATE/DELETE 触发器、卡片状态和日志同事务 | SQLite 测试验证触发器、外键与失败写入 |
| 完整导出/恢复 | `.reviewfault` ZIP、SHA-256 清单、数据库/媒体、回滚 | 协议测试与三端实现完成；Windows 实际导出/恢复通过，跨设备互恢复待平台 CI |

额外宿主证据：Windows 的同一份 `AppRepository.cs` 和 `NativeScheduler.cs` 已在 Linux/.NET 8 下加载真实 `libreviewfault_core.so`，完成建库、UUIDv7 建卡、结构化枚举卡、多图数学题、编辑、评分、搜索、备份与恢复集成测试。界面使用纯 C# WinUI 控件，不再依赖 XAML 编译器；托管编译已通过，Windows SDK 仍负责 PRI 与最终应用打包。

Android 证据：SDK 35、NDK r27、CMake 3.22.1 下 `assembleDebug` 与 `lintDebug` 成功；APK 包含 arm64-v8a/x86_64 两份 `libreviewfault.so` 和唯一 `001_initial.sql`，并通过 APK Signature Scheme v2 校验。

当前仍不能声明三个安装包均已验证。只有 HarmonyOS、Windows 对应 runner 构建成功，并完成三端黄金样例与跨端恢复组合后，完整目标才算通过最终审计。
