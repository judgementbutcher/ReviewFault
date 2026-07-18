# ReviewFault

ReviewFault 是面向考研数学与 408 的本地优先间隔学习软件。它不是把两类内容都压成普通闪卡：数学侧强调低摩擦记录、重做与错因复盘；408 侧强调主动回忆、分层提示与知识点关联。

当前版本为 **0.3.2**（schema v3、调度 ABI v3、备份协议 v3），包含共享核心和两个原生客户端：

- Android：Kotlin + Jetpack Compose
- Windows：C# + WinUI 3
- 共享内核：无第三方依赖的 C++20，通过稳定的 C ABI 接入两个客户端

## 宿主环境可运行内容

```sh
make test
```

这会构建调度内核和真实共享库，运行算法、领域、动态 ABI、SQLite、备份协议及平台源码契约测试。需要支持 C++20 的编译器和带 `node:sqlite` 的 Node.js 22+；也可单独运行 `make core-test`。两端安装包需在各自 SDK 环境中构建，见[构建说明](docs/building.md)。

Android 调试 APK 在完成 Android 构建后位于 `apps/android/app/build/outputs/apk/debug/app-debug.apk`；正式版本由 tag workflow 使用持久证书生成可覆盖升级的 release APK。Windows 同时发布不依赖预装 .NET/Windows App Runtime 的 MSI 安装包与便携 ZIP。仓库自带带 SHA-256 固定的 Gradle wrapper。

## 文档

- [产品定义](docs/product.md)
- [技术架构](docs/architecture.md)
- [调度算法](docs/scheduler.md)
- [跨端数据契约](docs/data-model.md)
- [原生端调度绑定](docs/native-bindings.md)
- [备份与恢复协议](docs/backup.md)
- [构建与验证](docs/building.md)
- [需求验证矩阵](docs/verification.md)
- [视觉与无障碍 token](docs/design-tokens.md)
- [v0.2 调度回放基线](docs/baseline-v0.2.md)
- [v0.3.2 开发计划](docs/roadmap-v0.3.2.md)
- [v0.3.2 发布说明](docs/release-v0.3.2.md)

`fixtures/scheduler_v1.tsv` 保留用于历史重放；`fixtures/memory_scheduler_v2.tsv` 与
`fixtures/math_scheduler_v2.tsv` 保留冻结的 v2 黄金样例；`fixtures/*_scheduler_v3.tsv`
固定 v3 决策结果。JNI 和 P/Invoke 绑定只有在
相同输入产生相同状态与 UTC 到期秒时才算接入完成。

## v0.3 已实现

- 今日—题库—添加—设置的信息结构、每天 20 个新 408 默认上限；
- 408 FSRS-6 与数学 Mastery Ladder 两套独立调度路径；
- 题库分页/筛选契约、标签、软删除、短时撤销与回收站恢复；
- 学习/算法设置以及设备独立的外观和提醒设置；
- 数学图片或文本录入、错因/错误步骤/关键提示、搜索和重做历史；
- 408 问答、填空、分层提示、枚举与对比卡；
- 完全离线的 SQLite 数据、不可变复习日志；
- Android、Windows `.reviewfault` v3 完整备份与 v1/v2/v3 恢复；
- Android JNI、Windows P/Invoke 共用同一调度核心。
- 匿名本地回放评估、参数注册表与逐事件决策快照；
- schema/备份/C ABI v3，同时继续恢复 v1/v2 备份并保留 v1/v2 回放入口；
- 408 小样本保护、逾期与连续遗忘保护，数学错因分流、连续失败优先和长期熟练延长；
- 可逆的“使用 v0.3 调度/继续 v0.2 参数”开关，切换不改写历史事件。
- 带积压保护的专注轮次：时间预算装不下到期复习时暂停引入新内容；
- 首页展示本轮预计时长、剩余到期负载以及明日/未来 7 天学习压力。
- 专注轮次支持无评分跳过与提前结束；跳过项同轮不再出现，下一轮按原到期时间恢复。

仍不纳入 v0.3：永久清除、图示遮挡编辑器、OCR、云同步和跨设备自动同步。个人参数只在本地历史达到 200 条且回放校准误差至少改善 0.01 时启用；数据不足时继续使用冻结默认参数。
