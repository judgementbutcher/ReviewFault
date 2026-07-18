# 构建与验证

## 共享核心（Linux/macOS）

```sh
make test
```

该命令执行：调度算法与黄金样例、领域规则、真实共享库动态加载、SQLite schema、备份协议和三端源码契约测试。也可使用 CMake：

```sh
cmake -S . -B build -DREVIEWFAULT_BUILD_TESTS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

## Android

用 Android Studio 打开 `apps/android`，安装 Android SDK 35、NDK（arm64-v8a/x86_64）和 CMake 3.22.1，然后运行 `app`。命令行环境可执行：

```sh
cd apps/android
./gradlew :app:assembleDebug :app:lintDebug
```

数据库 schema 直接从仓库根目录加入 app assets；CMake 直接编译共享核心，不复制算法源码。本仓库已在 SDK 35、NDK r27、CMake 3.22.1 上通过 `assembleDebug` 与 `lintDebug`。首次启动、图片选择和备份恢复仍应在 API 26、API 35 各做一次设备测试。

正式发布必须使用可跨版本复用的签名证书，不能发布 CI 临时 debug 签名。release workflow
需要仓库 secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`ANDROID_CERT_SHA256`。其中第一个值是
JKS/PKCS12 文件的 base64 内容，最后一个值是证书 SHA-256 指纹（小写且不含冒号）；
私钥本身不得提交到仓库。配置完成后，`v0.2.2` tag 会构建并校验
`assembleRelease`，生成可覆盖升级的正式 APK。

## HarmonyOS

用 DevEco Studio 打开 `apps/harmony`，选择 HarmonyOS SDK API 13 或更高版本，并为 `default` product 配置签名。运行 entry 模块。CMake 在构建期把根目录的 schema 注入 NAPI 模块；不要手工复制 SQL。

正式发布由带有 `self-hosted`、`linux`、`harmonyos` 标签的组织 runner 构建；该 runner 需要安装
HarmonyOS SDK API 13 或更高版本，并把 `ohpm`、`hvigorw` 加入 `PATH`。release workflow
需要仓库 secrets：`HARMONY_KEYSTORE_BASE64`、`HARMONY_KEYSTORE_PASSWORD`、
`HARMONY_KEY_ALIAS`、`HARMONY_KEY_PASSWORD`、`HARMONY_CERTIFICATE_BASE64` 和
`HARMONY_PROFILE_BASE64`。三个 `BASE64` 值分别是发布 `.p12`、`.cer` 和 `.p7b` 文件的
base64 内容；签名材料只会写入 runner 临时目录。tag 发布和手动触发都会生成
`ReviewFault-harmony-v0.2.2.hap`，手动触发可用于给既有 `v0.2.2` Release 补充 HAP。

真机验收需覆盖 PhotoViewPicker、DocumentViewPicker、`RdbStore.backup/restore` 和跨端 `.reviewfault` 恢复。API 13 是最低版本，因为系统 zlib 从该版本起明确拒绝压缩包路径穿越。

## Windows

安装 Visual Studio 2022（Desktop C++ 与 Windows App SDK 工作负载）、CMake 和 .NET 8：

```powershell
dotnet build apps/windows/ReviewFault/ReviewFault.csproj -p:Platform=x64
```

MSBuild 会先通过 CMake 生成 `reviewfault_core.dll`，再复制到 WinUI 输出目录。ARM64 用 `-p:Platform=ARM64`。需在 Windows 10 1809+ 与 Windows 11 各做一次文件选择、图片显示和备份恢复测试。

Windows 仓储与 P/Invoke 另有不依赖 WinUI 的集成测试，可在装有 .NET 8 的 Linux 上复用宿主共享库：

```sh
LD_LIBRARY_PATH=build dotnet run --project apps/windows/ReviewFault.HeadlessTests
```

纯 C# WinUI 窗口也可在 Linux 上完成托管编译（最终 PRI/Windows App SDK 打包仍必须在 Windows）：

```sh
dotnet msbuild apps/windows/ReviewFault/ReviewFault.csproj -t:Compile \
  -p:Platform=x64 -p:SkipNativeBuild=true -p:EnableWindowsTargeting=true \
  -p:AppxGeneratePriEnabled=false -p:AppxGeneratePrisForPortableLibrariesEnabled=false \
  -p:AppxGetPackagePropertiesEnabled=false -p:IncludeProjectPriFile=false
```

## 跨端门禁

- 三端运行 `fixtures/scheduler_v1.tsv` 后结果必须逐字段一致；
- Android 导出的备份应能在 Windows 与鸿蒙恢复，反向组合也要覆盖；
- 恢复损坏哈希、非法路径、错误 schema 版本时必须保留原数据；
- 改动 schema、调度公式或 C ABI 时必须分别提升对应版本，不能静默复用版本 1。
