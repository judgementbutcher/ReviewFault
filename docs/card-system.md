# 科学制卡与复习协议

ReviewFault 的卡片不是一对字符串，而是“触发回忆的提示 + 可核对的证据 + 可定位的来源 + 可复盘的诊断”。`memory_card` 和 `math_problem` 仍保留旧表以兼容 v1-v4；新增字段集中在 `card_profile_v5`，复习结果写入不可变的 `learning_evidence_v5`。

## 字段分层

| 层 | 字段 | 用途 | 复习时机 |
|---|---|---|---|
| 触发 | `prompt`、`templateType`、`archetype` | 让问题足够具体，决定回忆动作 | 只在答案前展示 |
| 核对 | `answer`、`answerPoints`、`hints` | 逐条判断是否真正想起，而不是凭熟悉感评分 | 锁定作答后展示 |
| 解释 | `mechanism`、`conditions`、`contrast`、`example` | 解释为什么成立、何时成立以及边界 | 核对后按需展开 |
| 迁移 | `commonTrap`、`transferPrompt`、`mnemonic` | 把一次记忆转成下一次识别线索 | 复盘或变式练习 |
| 来源 | `sourceType`、`sourceTitle`、`sourceChapter`、`sourceLocator`、`sourceYear` | 快速回到教材、课程、真题或笔记原处 | 题库搜索和复习后 |
| 数学诊断 | `firstAttempt`、`errorTrigger`、`generalMethod`、`verification`、`targetSeconds` | 区分“不会”“路线错”“算错”“超时”，安排重做 | 数学重做与复盘 |
| 结构 | `structuredPayload` | 保存可机器核对的行、步骤或公式对象 | 量级映射/公式卡专用 |

字段为空时不伪造解释。编辑器只在该知识形式需要时显示对应字段，保存前检查必填项和结构化 JSON。

## 408 形式适配

### 概念与对比：吞吐量、响应时间

使用 `archetype=concept` 或 `comparison`，不要把截图中的整段文字直接当答案。

- `prompt`：`吞吐量和响应时间分别是什么？二者如何区分？`
- `answerPoints`：吞吐量是单位时间处理的请求数量；响应时间是单次请求从发出到结果返回的总等待时间。
- `mechanism`：输入、CPU、内存读写和 I/O 等环节共同限制吞吐量；响应时间还包含该请求排队和等待的时间。
- `contrast`：吞吐量是速率/总量视角，响应时间是单请求延迟视角；系统可能吞吐量高但单请求延迟仍高。
- `conditions`：注明单位时间、请求范围和是否包含排队/I/O 等口径。

评分点按可独立判断的事实拆分。只说“越快越好”不能命中任一点。

### 枚举与量级映射：MFLOPS 到 ZFLOPS

使用 `archetype=scale_mapping`，`templateType=enumeration`。每一行放进 `answerPoints`，同时写入 `structuredPayload.rows`：

```text
MFLOPS | 10^6 | 百万
GFLOPS | 10^9 | 十亿
TFLOPS | 10^12 | 万亿
PFLOPS | 10^15 | 千万亿
EFLOPS | 10^18 | 百亿亿
ZFLOPS | 10^21 | 十万亿亿
```

每行有 `term`、`exponent`、`magnitude` 三个字段，因此可以分别评估“单位名”“指数”“中文量级”。回忆问题应支持正向和反向两种问法，例如“`10^15` 对应什么？”；至少两个评分点是保存和评分的硬约束。

### 公式规则：补码表示法

使用 `archetype=formula_rule`，答案保存规则本身，`conditions` 保存位数和范围，`example` 保存边界代入，`commonTrap` 保存符号位/模数/负数绝对值的混淆。推荐拆成：

- 正数补码与原码相同；
- 负数补码等于模 `2^(n+1)` 与绝对值之差；
- 明确 `-2^n <= x < 0`、位数和溢出边界；
- 用一个正数、一个负数和最小值做验证。

这样复习时先回忆定义，再用边界例子验证，而不是只背一条公式。

## 数学错题生命周期

数学卡的主动作是“重做”，不是把正解背成问答卡。首次录入允许只保存题面和来源；补全诊断时按以下链条填写：

`firstAttempt -> errorTrigger -> errorReason -> generalMethod -> verification -> transferPrompt`

`errorReason` 使用固定枚举：`concept`、`approach`、`calculation`、`misread`、`forgotten_fact`、`timeout`、`other`。`targetSeconds` 只用于数学错题，范围为 10–7200 秒。数学复习先显示题面和草稿/笔迹，再显示解答与诊断；查看提示或直接看答案会限制本次评价上限，避免把辅助后的结果当成独立做对。

## 主动回忆与证据评分

每次复习按以下顺序执行：

1. 只看触发问题，写下回忆草稿，并记录 1–5 的作答前信心。
2. 必要时逐层请求提示；提示层级从弱线索到接近答案，不能跳过证据记录。
3. 点击“锁定作答并核对”后查看答案，按 `answerPoints` 勾选命中点，填写遗漏或误判。
4. 生成四档评价：忘记、困难、正确、轻松；直接看答案最高为忘记，使用提示会封顶，评分点覆盖率决定记忆卡的基础档位。
5. 将 `hintLevel`、`answerRevealed`、`pointHits`、`pointCount`、`confidence`、`reflection` 和耗时追加到 `learning_evidence_v5`，旧调度事件仍按兼容协议写入。

评分点是证据，不是让用户在答案页凭感觉点“熟练”。建议把一个答案拆成 2–7 个独立要点；太长的句子应拆开，纯措辞差异不要拆成新点。

## 标签约定

标签用于定位，不代替正文。编辑器会自动补充以下命名空间，用户标签可并存：

- `学科/数学`、`学科/操作系统` 等；
- `形式/概念`、`形式/对比`、`形式/量级映射`、`形式/公式规则`、`形式/错题`；
- `考点/<知识点>`、`来源/<书名或课程>`、`章节/<章节>`；
- 数学额外补充 `错因/<分类>`。

标签最多 30 个，单个不超过 60 个字符；同名标签大小写不敏感。搜索会同时匹配题面、答案、考点、来源和标签，跨设备同步使用 `cardProfile`、`tag` 与 observed-remove 关系事实。

## 跨端实现边界

Android、Windows、HarmonyOS 共用 v5 字段和评分语义。Android/Windows 支持图片题面导入；HarmonyOS 提供离线文本错题录入并可复习同步过来的图片错题。所有客户端都在本地事务中同时写内容、profile、标签和 outbox，远端只追加同步事实，不覆盖已有复习历史。
