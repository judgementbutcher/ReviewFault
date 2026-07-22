# v0.6 验证矩阵

| 范围 | 自动化证据 | 仍需发布 runner |
|---|---|---|
| 两套调度器 | 全评分、错因、三个预设、提示封顶、边界、回放、章节交错；真实 `.so` 动态符号 | 三端逐行运行冻结 fixtures |
| schema v5 | 真实 v1→v2→v3→v4→v5 升级、不可变学习证据、同步元数据、净化备份、事务回滚与外键 | 三端各自 SQLite/Rdb 版本 |
| 备份 v5 | v1/v2/v3/v4/v5 schema、路径与哈希约束、同步私有状态净化 | Android/Windows 互恢复与损坏包真机验证 |
| 同步 | outbox/游标/修订/冲突 schema、后端契约、Windows 拉取回放和防回环集成测试 | PostgreSQL/对象存储端到端与断网重试 |
| 回放与性能 | 匿名回放五项指标、v0.2 冻结基线、黄金/边界样例、5,000 条队列预算 | 真实历史只在用户设备本地运行 |
| 专注轮次与预报 | 到期积压抑制新内容、显式关闭保护、7 日桶边界、双端 SQL 一致性 | 跨午夜和系统时区切换真机验证 |
| 会话跳过与结束 | 双端排除首项/全部候选、排除查询零事件写入、清空后重新可见、平台入口契约 | 两端连续跳过、提前结束与系统返回键真机验证 |
| 题库/设置 | 领域分页与设置校验；平台仓储包含标签、软删除、恢复和设置 | 5,000 条数据性能与 UI 自动化 |
| 原生 UI | Android 响应式 Compose、Windows NavigationView、Harmony Stage/ArkUI 源码契约；三端洞察字段与现代暗黑 token | 动态字体、读屏、暗色对比、减少动态效果与响应式真机审计 |
| v5 客户端接入 | 三端已接入 `card_profile_v5`、`learning_evidence_v5`、`cardProfile` 同步实体、主动回忆证据和任务投影；Windows 已按 v5 任务队列选题 | Android/Harmony 仍需切换为完整 v5 阶段队列；三端 SDK/真机验证图片导入、断网回放和跨端恢复 |

本轮宿主门禁包括 `make test`、Windows 仓储集成测试、Linux 上的 WinUI 托管编译、
同步服务 Release 编译，以及 Android arm64-v8a/x86_64 debug 构建与 lint。
verify/release runner 还必须完成 v5 阶段队列的全端接入、Android 双 ABI 构建与 lint、Windows 自包含发布、MSI 安装/卸载，
HarmonyOS 6 签名 HAP 与 NAPI/Rdb 真机门禁，通过后才允许生成 tag 资产。正式 Android 资产由
仓库持久证书签名，Windows 原生包由 Windows runner 生成。API 26/35、Windows 10/11、
HarmonyOS 6 平板以及三端互恢复仍需设备验收。
