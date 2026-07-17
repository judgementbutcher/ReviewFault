# 原生端调度绑定

三个客户端都调用 `reviewfault_c.h` 所定义的 ABI v1，不在 Kotlin、ArkTS 或 C# 中复制公式。

## Android

`apps/android/app/src/main/cpp/scheduler_jni.cpp` 把 Kotlin 的卡片字段传给 C ABI，并返回强类型 `NativeScheduleResult`。Gradle 的 externalNativeBuild 会直接编译仓库中的核心源码，产出 `libreviewfault.so`。支持 arm64-v8a 与用于模拟器测试的 x86_64。

## HarmonyOS

`apps/harmony/entry/src/main/cpp/napi_scheduler.cpp` 通过 Native API 暴露 `abiVersion` 和 `review`，ArkTS 的 `NativeScheduler.ets` 提供类型封装。所有时间都使用安全整数范围内的 UTC Unix 秒。

## Windows

`apps/windows/ReviewFault/Core/NativeScheduler.cs` 使用 P/Invoke。启动与每次调用前会同时核对 ABI 版本和三个结构体尺寸，避免平台对齐或旧 DLL 造成静默数据损坏。Windows 工程需要把 CMake 生成的 `reviewfault_core.dll` 放到应用输出目录。

## 接入门禁

每个平台的集成测试必须读取 `fixtures/scheduler_v1.tsv` 并断言全部字段；还必须覆盖错误 ABI、无效评分和 UTC 秒超过范围。当前宿主环境不含 Android SDK、DevEco Studio 或 Windows SDK，所以平台工程的实际编译仍需在对应 CI runner 上完成，不能用宿主 C++ 测试替代该门禁。

