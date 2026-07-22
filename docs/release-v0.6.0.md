# ReviewFault v0.6.0 (development candidate)

> This document describes the v0.6.0 data and scheduler contract. It is not a
> release note until every client uses the v5 task queue and writes v5 learning
> evidence during review. The current Android, Windows, and HarmonyOS review
> screens still use the frozen v1-v4 review path for their visible queue and
> ratings.

v0.6.0 把复习从“单张内容的下一次到期”推进到可验证的学习任务。数学错题以修补、原题、变式、迁移和保持组成阶段路线；408 以知识包中的主动回忆证据衡量掌握，而不是把看过答案后的熟悉感当作独立回忆。

## 学习任务与证据

- 新增知识单元、学习任务、关系和不可变学习证据；所有新事实可随同步传递，旧 v1-v4 复习历史仍按原算法回放；
- 数学任一失败、提示或错因都会回到修补阶段；没有关联变式时明确停在“等待变式”，不会错误毕业；
- 408 按要点覆盖、提示或答案暴露、可靠作答时长与信心换算有效评分；看过提示或答案最高为“困难”；
- 新的会话规划优先处理修补和到期任务，并在时间不足时报告复习债、暂停可选新学；
- 个人化间隔调整需要至少 20 条证据，并向默认目标保守收缩。

## 兼容性

- schema、调度 C ABI 与备份协议均升级到 v5；
- v1-v4 数据库与备份顺序迁移到 v5，未知版本在替换本地数据前拒绝；
- Android 与 HarmonyOS `versionCode` 提升到 11；Windows、Android 和 HarmonyOS 的应用版本统一为 0.6.0。

## Release gate

Before creating a `v0.6.0` tag, all three clients must select sessions through
`plan_session_v5`, collect the task-specific review fields, call the v5
scheduler, and append `learning_evidence_v5` in the same transaction as the
task projection. The settings surfaces must also provide an update check with
the published release as its source of truth.

## Planned assets

- Android：`ReviewFault-android-v0.6.0.apk`；
- Windows：`ReviewFault-windows-v0.6.0-x64.msi` 和自包含便携 ZIP；
- HarmonyOS：可由受管签名 Runner 构建并作为签名 HAP 附件发布。
