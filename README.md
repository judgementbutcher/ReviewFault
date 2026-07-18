# ReviewFault

ReviewFault 是面向考研数学与 408 的本地优先间隔学习软件。它不是把两类内容都压成普通闪卡：数学侧强调低摩擦记录、重做与错因复盘；408 侧强调主动回忆、分层提示与知识点关联。

当前版本为 **0.2.3**（schema v2、调度 ABI v2、备份协议 v2），包含共享核心和两个原生客户端：

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
- [v0.3 后端与间隔学习升级计划](docs/roadmap-v0.3.md)

`fixtures/scheduler_v1.tsv` 保留用于历史重放；`fixtures/memory_scheduler_v2.tsv` 与
`fixtures/math_scheduler_v2.tsv` 是两套跨端黄金样例。JNI 和 P/Invoke 绑定只有在
相同输入产生相同状态与 UTC 到期秒时才算接入完成。

## v0.2 已实现

- 今日—题库—添加—设置的信息结构、每天 20 个新 408 默认上限；
- 408 FSRS-6 与数学 Mastery Ladder 两套独立调度路径；
- 题库分页/筛选契约、标签、软删除、短时撤销与回收站恢复；
- 学习/算法设置以及设备独立的外观和提醒设置；
- 数学图片或文本录入、错因/错误步骤/关键提示、搜索和重做历史；
- 408 问答、填空、分层提示、枚举与对比卡；
- 完全离线的 SQLite 数据、不可变复习日志；
- Android、Windows `.reviewfault` v2 完整备份与 v1/v2 恢复；
- Android JNI、Windows P/Invoke 共用同一调度核心。

仍不纳入 v0.2：永久清除、图示遮挡编辑器、OCR、云同步、个人参数训练与跨设备自动同步。发布前仍必须完成两套平台 SDK 构建和真机互恢复验收。
