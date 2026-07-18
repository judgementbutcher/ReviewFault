# v0.3 验证矩阵

| 范围 | 自动化证据 | 仍需发布 runner |
|---|---|---|
| 两套调度器 | 全评分、错因、三个预设、提示封顶、边界、回放、章节交错；真实 `.so` 动态符号 | 两端逐行运行 v2 fixtures |
| schema v3 | 真实 v1→v2→v3 升级、参数注册表、不可变 v2/v3 事件、决策快照、事务回滚与外键 | 两端各自 SQLite 版本 |
| 备份 v3 | v1/v2/v3 schema、路径与哈希约束；两端源码实现兼容组合和顺序迁移 | v1/v2→v3、v3↔v3 真机互恢复与损坏包 |
| 回放与性能 | 匿名回放五项指标、v0.2 冻结基线、黄金/边界样例、5,000 条队列预算 | 真实历史只在用户设备本地运行 |
| 题库/设置 | 领域分页与设置校验；平台仓储包含标签、软删除、恢复和设置 | 5,000 条数据性能与 UI 自动化 |
| 原生 UI | Android 设置/提醒/回收站，Windows NavigationView | 动态字体、读屏、暗色对比与响应式真机审计 |

本轮已在宿主环境运行 `make test`、Windows 仓储集成测试和 Linux 上的 WinUI 托管编译。
verify/release runner 还必须完成 Android 双 ABI 构建与 lint、Windows 自包含发布、MSI 安装/卸载，
通过后才允许生成 tag 资产。正式 Android 资产由仓库持久证书签名，Windows 原生包由 Windows
runner 生成。API 26/35、Windows 10/11 以及两端互恢复仍需设备验收。
