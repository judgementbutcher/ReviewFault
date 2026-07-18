# ReviewFault v0.2.3

这是 Windows 安装与发布链路修复版本。schema、调度 ABI、参数版本和备份协议均保持 v2，
学习记录与 v0.2.2 完全兼容。

## 主要变化

- Windows 改为自包含发布，同时携带 .NET 8 和 Windows App SDK 1.6 运行时；
- 修复在未预装 Windows App Runtime 的电脑上启动时提示需要 1.6 运行时、应用无法运行的问题；
- 新增 `ReviewFault-windows-v0.2.3-x64.msi`，提供安装路径选择、开始菜单快捷方式、覆盖升级和卸载；
- 保留自包含便携 ZIP，解压即可运行，不再要求用户手动安装 Windows App Runtime；
- Windows CI 新增运行时文件检查，以及 MSI 静默安装、文件验证和卸载冒烟测试；
- Android 仅同步版本号，业务功能、数据库和调度算法不变。

## 下载与升级

- Windows（推荐）：下载并运行 `ReviewFault-windows-v0.2.3-x64.msi`；支持 Windows 10 1809 或更高；
- Windows（便携）：下载 `ReviewFault-windows-v0.2.3-x64.zip`，完整解压后运行 `ReviewFault.exe`；
- Android：安装 `ReviewFault-android-v0.2.3.apk`，最低 API 26。

MSI 升级和卸载不会删除 `%LOCALAPPDATA%\ReviewFault` 中的本地数据库。大量录入或跨版本升级前，
仍建议先导出一份 `.reviewfault` 备份。
