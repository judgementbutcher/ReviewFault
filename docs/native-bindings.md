# 原生端调度绑定 v3

两个客户端只通过 `reviewfault_c.h` 调用共享公式。ABI 总版本为 3；旧 `rf_review` 及 v2 入口继续导出
用于历史重放，新作答默认调用 `review_memory_v3` 与 `review_math_v3`。

- Android：JNI 暴露 `nativeReviewMemoryV2` / `nativeReviewMathV2`，构建 arm64-v8a 与 x86_64；
- Windows：P/Invoke 在调用前核对 ABI 版本以及四个 v2 状态/结果结构尺寸。

所有结构带 `struct_size`，布尔值跨 ABI 使用 32 位整数，时间使用 `int64_t` UTC 秒。
平台启动会运行冻结黄金探针；动态 ABI 测试还通过 `dlopen` 验证未加前缀的两个契约符号
真实存在。v3 绑定还会核对结果结构尺寸、算法/参数版本和决策标志。发布门禁必须逐行验证黄金
fixture，而不能以宿主单元测试代替平台绑定测试。
