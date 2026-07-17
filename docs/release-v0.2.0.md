# ReviewFault v0.2.0

本版本将产品扩展为“今日—题库—添加—设置”的完整离线学习闭环。

## 主要变化

- 408 使用冻结参数的 Memory FSRS-6，提供省时、均衡、强化三种保留率预设；
- 数学使用独立的 Mastery Ladder v2，按熟练度、错因与强度安排重做；
- 题库支持编辑、标签、筛选、批量软删除、短时撤销和回收站恢复；
- 新增学习、算法、明暗外观和本地提醒设置；
- Android 主界面迁移至 Compose Material 3 + ViewModel/StateFlow；
- HarmonyOS 拆分 UI 状态/ViewModel，Windows 使用 NavigationView、ViewModel 与组件 token；
- schema、调度 ABI 与备份协议统一升级至 v2，仍可恢复 v1 备份。

## 下载

- Android debug APK：最低 API 26；
- Windows x64 ZIP：解压后运行，Windows 10 1809 或更高；
- HarmonyOS：源代码已同步升级，签名 HAP 需组织的 DevEco/HarmonyOS runner 生成。

所有发布资产的 SHA-256 见 `SHA256SUMS`。数据升级前建议先在 v0.1 导出完整备份。
