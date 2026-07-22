# HarmonyOS 发布与 GitHub Runner 配置手册

本文用于为 ReviewFault 配置 HarmonyOS 6 正式构建机，并完成签名 HAP 的 GitHub Release 发布。

当前发布工作流位于 `.github/workflows/release.yml`，Harmony job 要求 Runner 同时拥有以下标签：

```text
self-hosted
harmonyos-6
api-20
```

工作流成功后会生成：

```text
ReviewFault-harmony-v0.4.0-signed.hap
```

并与 Android APK、Windows MSI/ZIP、同步服务镜像摘要一起生成 GitHub Release 和
`SHA256SUMS`。

## 1. 先判断 VPS 是否合适

华为当前为 HarmonyOS 应用开发列出的 DevEco Studio 主机系统主要是：

- Windows 10/11 64 位：至少 16GB 内存、100GB 可用磁盘；
- macOS：至少 8GB 内存、100GB 可用磁盘。

普通 Linux VPS 不在 DevEco Studio 应用开发工具链的官方支持范围内，不建议把正式签名发布建立在
复制 SDK、非官方 Docker 镜像或社区工具链上。

推荐顺序：

1. 一台专用 Windows 10/11 x64 电脑或虚拟机；
2. 一台专用 macOS 构建机；
3. 支持 Windows 10/11 的云主机；
4. 不使用普通 Linux VPS 构建 HAP。

Linux VPS 仍可用于部署 ReviewFault 同步服务，但不承担 HarmonyOS HAP 构建。

官方参考：

- [DevEco Studio](https://developer.huawei.com/consumer/cn/deveco-studio/)
- [下载与安装 DevEco Studio](https://developer.huawei.com/consumer/cn/doc/doccenter-deveco-studio/ide-software-install)

## 2. 准备构建机器

以 Windows 10/11 x64 为例，安装：

- 最新稳定版 DevEco Studio；
- HarmonyOS 6 / API 20 SDK；
- Git for Windows；
- `jq`；
- 提供 GNU `strings` 的 binutils，例如 MSYS2 binutils。

DevEco Studio 已集成 HarmonyOS SDK、Node.js、Hvigor 和 OHPM，不要从来历不明的网站下载这些工具。

安装后在 PowerShell 中定位实际路径：

```powershell
Get-ChildItem "C:\Program Files\Huawei" -Recurse -Filter hvigorw.bat
Get-ChildItem "C:\Program Files\Huawei" -Recurse -Filter ohpm.bat
Get-ChildItem "C:\Program Files\Huawei" -Recurse -Filter hap-sign-tool.jar
```

记录以下内容：

```text
Hvigor 可执行文件路径
OHPM bin 目录
HAP 签名校验工具或 hap-sign-tool.jar 路径
DevEco Studio 使用的 Java 路径
```

## 3. 创建正式应用身份与签名材料

在华为开发者平台创建应用，Bundle Name 必须为：

```text
cn.reviewfault.app
```

通过 DevEco Studio 配置正式 Release 签名。需要保存的材料通常包括：

```text
密钥库，例如 .p12
签名证书，例如 .cer
发布 Profile，例如 .p7b
密钥别名
密钥库密码
私钥密码
```

不要使用仅限调试设备的临时签名作为公开发布身份。签名身份一旦用于公开发布，应加密备份；丢失后可能
无法用相同身份覆盖升级。

建议在构建机建立专用目录：

```text
C:\harmony-signing\
```

只允许专用 Runner 账户读取该目录，不要将其中任何文件提交到 Git。

## 4. 生成 signingConfigs JSON

先让 DevEco Studio 成功配置一次正式 Release 签名，然后复制它生成的
`app.signingConfigs` 数组。不要凭空猜测字段；以下内容只展示结构：

```json
[
  {
    "name": "release",
    "type": "HarmonyOS",
    "material": {
      "certpath": "C:/harmony-signing/release.cer",
      "storePassword": "密钥库密码",
      "keyAlias": "reviewfault",
      "keyPassword": "私钥密码",
      "profile": "C:/harmony-signing/release.p7b",
      "signAlg": "SHA256withECDSA",
      "storeFile": "C:/harmony-signing/reviewfault.p12"
    }
  }
]
```

必须满足：

- 数组中存在名为 `release` 的配置；
- 所有文件路径都是构建机上的绝对路径；
- 路径所指文件真实存在；
- JSON 中的密码与密钥库匹配；
- Bundle Name 与 `cn.reviewfault.app` 一致。

保存为：

```text
C:\harmony-signing\signing-config.json
```

## 5. 配置 GitHub Actions Secret

PowerShell 中将完整 JSON 文件编码为 Base64：

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("C:\harmony-signing\signing-config.json")
) | Set-Clipboard
```

进入仓库：

```text
Settings
→ Secrets and variables
→ Actions
→ New repository secret
```

创建：

```text
名称：HARMONY_SIGNING_CONFIG_BASE64
值：剪贴板中的 Base64 文本
```

Base64 只是编码，不是加密。原始 JSON、密码和签名文件都不应写入仓库或普通日志。

## 6. 准备 HAP 签名验证包装程序

工作流通过环境变量 `HAP_VERIFY_TOOL` 调用签名验证程序，调用约定为：

```text
HAP_VERIFY_TOOL 要校验的文件.hap
```

程序必须：

- 对 HAP 做真实的密码学签名验证；
- 验证成功时返回退出码 `0`；
- 签名无效或文件损坏时返回非零退出码。

先查看当前 DevEco Studio 附带工具的帮助：

```powershell
java -jar "实际路径\hap-sign-tool.jar" --help
```

根据该版本的参数建立包装程序，例如：

```text
C:\reviewfault-tools\verify-hap.cmd
```

不同 DevEco Studio 版本的命令行参数可能不同，应以本机 `--help` 输出为准，不要直接复制其他版本的
参数。完成后先手动验证一个已签名 HAP，确认有效文件返回 `0`，篡改后的文件返回非零。

## 7. 配置 Runner 所需环境变量

启动 Runner 前，在 PowerShell 中设置当前会话环境。下面的路径需要替换为实际路径：

```powershell
$env:HVIGORW = "C:/实际路径/hvigorw.bat"
$env:HAP_VERIFY_TOOL = "C:/reviewfault-tools/verify-hap.cmd"
$env:PATH += ";C:\实际路径\ohpm\bin;C:\msys64\usr\bin"
```

工作流还会通过 `actions/setup-node` 安装 Node.js 22。

用 Git Bash 检查工作流依赖：

```powershell
bash -lc 'test -x "$HVIGORW"'
bash -lc 'test -x "$HAP_VERIFY_TOOL"'
bash -lc 'command -v ohpm'
bash -lc 'command -v jq'
bash -lc 'command -v strings'
bash -lc 'command -v base64'
bash -lc 'command -v find'
bash -lc 'command -v grep'
```

所有命令都必须成功。尤其要注意：GitHub Runner 作为 Windows 服务运行时不会自动继承后来打开的终端
环境。首次发布建议采用前台、一次性 Runner，让它直接继承当前 PowerShell 的环境。

## 8. 注册一次性 GitHub Runner

进入：

```text
GitHub 仓库
→ Settings
→ Actions
→ Runners
→ New self-hosted runner
```

选择 Windows x64，并使用页面实时显示的下载和解压命令。注册 token 会过期，不要保存或提交它。

在 Runner 目录运行：

```powershell
.\config.cmd `
  --url https://github.com/judgementbutcher/ReviewFault `
  --token GitHub页面提供的临时TOKEN `
  --name harmony-release-01 `
  --labels harmonyos-6,api-20 `
  --ephemeral `
  --unattended
```

GitHub 会自动添加 `self-hosted` 和系统架构标签。仓库工作流要求至少同时存在：

```text
self-hosted
harmonyos-6
api-20
```

官方参考：

- [添加自托管 Runner](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners)
- [在工作流中使用自托管 Runner](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/use-in-a-workflow)

## 9. 启动 Runner 并接收发布任务

确认签名文件、Secret、环境变量和验证程序全部就绪后，再启动：

```powershell
.\run.cmd
```

正常情况下会看到：

```text
Connected to GitHub
Listening for Jobs
```

如果 `v0.4.0` 发布任务仍在排队，Runner 会自动接单。当前发布页面为：

```text
https://github.com/judgementbutcher/ReviewFault/actions/runs/29665824840
```

如果等待期间工作流已经超时或取消，在 GitHub Actions 页面选择 `Re-run all jobs`。不要为同一版本随意
强推或移动 tag。

## 10. Harmony job 实际执行内容

当前工作流会依次执行：

1. 检查 tag 必须为 `v0.4.0`；
2. 检查 `HVIGORW` 与 `HAP_VERIFY_TOOL`；
3. 检查 `jq`、`ohpm`、`strings`；
4. 解码 `HARMONY_SIGNING_CONFIG_BASE64`；
5. 确认数组中存在名为 `release` 的签名配置；
6. 将签名配置注入 `apps/harmony/build-profile.json5`；
7. 准备 SQLite 与协议 contract rawfiles；
8. 执行 `ohpm install --all`；
9. 执行 Hvigor Release 构建；
10. 验证 HAP 签名；
11. 检查 HAP 中的 `cn.reviewfault.app`；
12. 上传签名 HAP artifact。

核心构建命令为：

```text
assembleHap --mode module -p product=default -p buildMode=release
```

## 11. 成功后的发布结果

Harmony job 成功后，`publish` job 会自动下载全部平台 artifact，并生成：

```text
ReviewFault-android-v0.4.0.apk
ReviewFault-harmony-v0.4.0-signed.hap
ReviewFault-windows-v0.4.0-x64.msi
ReviewFault-windows-v0.4.0-x64.zip
sync-image-digest.txt
SHA256SUMS
```

随后创建：

```text
ReviewFault v0.4.0
```

的 GitHub Release。

该流程只发布 GitHub Release 附件，不会自动提交华为应用市场。华为应用市场上架是另一套审核与发布流程。

## 12. 常见故障

### Job 一直 queued

检查 Runner 页面是否为 `Online`，并确认标签完全匹配：

```text
self-hosted
harmonyos-6
api-20
```

### `HVIGORW is required`

Runner 进程没有继承 `HVIGORW`，或路径包含错误字符。重新打开 PowerShell、设置环境变量，再从同一终端
启动 `run.cmd`。

### 找不到 `ohpm`

将 DevEco Studio 的 OHPM `bin` 目录加入启动 Runner 的 `PATH`，然后运行：

```powershell
bash -lc 'ohpm --version'
```

### 找不到 `jq` 或 `strings`

确认 MSYS2 或同类工具目录已经加入 `PATH`，且 Git Bash 可以找到命令。只在 PowerShell 中能执行并不足够。

### 签名配置校验失败

确认 Secret 解码后是 JSON 数组，并且存在：

```json
{ "name": "release" }
```

### 找不到证书或 Profile

Secret 中保存的是路径，不是文件内容。相关签名文件必须预先存在于 Runner 构建机。

### Hvigor 编译失败

在 DevEco Studio 中确认安装的是 HarmonyOS 6 / API 20，并用同一 SDK 手动打开
`apps/harmony` 工程进行一次构建。不要用 OpenHarmony 或旧 API 的 SDK 代替。

### HAP 验证失败

先在 Runner 上直接运行 `HAP_VERIFY_TOOL` 包装程序。确认它调用的是当前 DevEco Studio 附带的验证工具，
并且成功与失败时的退出码正确。

### 发布完成但没有 GitHub Release

检查 `publish` job。它要求 core、backend、Android、Harmony 和 Windows 全部成功，任何一个 job 失败都
不会创建最终 Release。

## 13. 安全收尾

ReviewFault 当前是公开仓库。GitHub 官方提醒：公开仓库的自托管 Runner 可能执行来自不可信贡献者的
代码，因此签名构建机必须专用并隔离。

建议：

- 使用 `--ephemeral` 一次性 Runner；
- 不在机器上存放其他项目或云平台凭据；
- 发布前检查待执行 workflow 和 tag 对应提交；
- 签名目录只允许专用账户读取；
- 发布完成后立即关闭或销毁 Runner；
- 删除临时工作目录和 Runner 注册信息；
- 将签名身份离线加密备份；
- 定期检查证书和 Profile 的有效期。

不要把签名构建机配置为公开仓库长期在线的通用 Runner。

## 14. 发布前最终检查表

- [ ] 构建机是受支持的 Windows 10/11 x64 或 macOS；
- [ ] 安装了稳定版 DevEco Studio；
- [ ] 安装了 HarmonyOS 6 / API 20；
- [ ] Bundle Name 是 `cn.reviewfault.app`；
- [ ] 正式签名材料已生成并离线备份；
- [ ] `signingConfigs` 中存在 `release`；
- [ ] `HARMONY_SIGNING_CONFIG_BASE64` 已创建；
- [ ] `HVIGORW` 指向真实可执行文件；
- [ ] `HAP_VERIFY_TOOL` 能验证签名并返回正确退出码；
- [ ] Git Bash 能找到 `ohpm`、`jq`、`strings`、`base64`、`find`、`grep`；
- [ ] Runner 标签包含 `harmonyos-6` 与 `api-20`；
- [ ] Runner 使用一次性 `--ephemeral` 配置；
- [ ] 已检查待执行 tag 和 workflow；
- [ ] 发布完成后关闭或销毁 Runner。
