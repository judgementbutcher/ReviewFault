# v0.2 验证矩阵

| 范围 | 自动化证据 | 仍需发布 runner |
|---|---|---|
| 两套调度器 | 全评分、错因、三个预设、提示封顶、边界、回放、章节交错；真实 `.so` 动态符号 | 三端逐行运行 v2 fixtures |
| schema v2 | 真实 v1 数据升级、到期保留、重复迁移、类型状态、不可变事件、软删恢复、外键 | 三端各自 SQLite 版本 |
| 备份 v2 | v1/v2 schema、路径与哈希约束；三端源码实现兼容组合和迁移 | v1→v2、v2↔v2 真机互恢复与损坏包 |
| 题库/设置 | 领域分页与设置校验；平台仓储包含标签、软删除、恢复和设置 | 5,000 条数据性能与 UI 自动化 |
| 原生 UI | Android 设置/提醒/回收站，Harmony ArkUI 页面，Windows NavigationView | 动态字体、读屏、暗色对比与响应式真机审计 |

本轮已在宿主环境运行 `make test`、Windows 仓储集成测试、Linux 上的 WinUI 托管编译，
以及 Android debug/release 的双 ABI 构建与 lint；release APK 还使用临时测试证书完成了
签名结构校验。正式 Android 资产必须由仓库持久证书重新签名，Windows 原生包必须由
Windows runner 生成，HAP 必须由组织的签名 HarmonyOS runner 生成。API 26/35 真机、
Windows 10/11 以及三端互恢复仍属于发布后的设备验收。
