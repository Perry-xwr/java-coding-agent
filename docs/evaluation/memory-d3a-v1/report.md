# D3-A Memory 离线评测与同案例复测

## 测试集和证据范围

12 个合成案例，四类各 3 条：事实 F01–F03、改写 P01–P03、干扰/权限 N01–N03、
更新/删除/过时保护 V01–V03。冻结输入位于 `benchmark/memory-d3a-v1/cases.json`，
包含目的、初始记忆/会话开关/动作、用户问题、相关标签和预期行为。
原始结果逐例记录召回内容、注入标签、上下文门控、权限/版本、通过状态和失败原因。

原始实验目录：`D:\Development\agent-cli\benchmark-runs\memory-d3a-20261101-d3e85bfb`。
其中 baseline.json 在修改前创建，after.json 是修改后的同案例结果；另保存改前源码、
frozen-hashes.json 及互相隔离的两阶段 Memory 工作区。基线未覆盖，测试集哈希一致。

使用真实本地 SharedMemoryStore、关键词检索、RequestContextSelector，FakeLLM 实际
接收选定 messages；回复固定且不评分。无 Embedding、无真实模型或外部服务请求。
没有读取 D2 账本或个人记忆。这里测的是检索及请求构建边界，不是完整真实 Coding Agent 能力。

## 指标与分母

| 指标 | 定义 | 改前 | 改后 |
| --- | --- | --- | --- |
| Hit@3 | 需要正相关记忆的 8 个案例中，前三条包含标注相关项；空结果案例不进入分母 | 5/8，62.5% | 6/8，75.0% |
| MRR@3 | 同 8 案例首个相关项倒数排名的平均值，未命中计 0 | 0.625 | 0.750 |
| 完整案例通过 | 同时满足召回或应为空、注入、无无关项及权限版本条件 | 9/12 | 10/12 |
| 权限安全率 | N01 global OFF、N03 外工作区记忆不得注入 | 2/2 | 2/2 |
| 版本安全率 | 更新/删除/过时写入三例不得注入旧版本或越过过时保护 | 3/3 | 3/3 |
| 无关误注入 | 全部 12 个请求中不属于相关标签的注入条目总数，不是比例 | 0 | 0 |

V01/V03 实际召回 Java 21，V02 删除后为空。权限/版本合计 5/5，不是 12/12 权限测试。
本组相关结果均排第一，因此 MRR 与 Hit@3 数值相同，不能推断排序普遍改善。

## 失败依据与唯一生产改进

基线 P01、P02、P03 均漏召回。选择 P03：问题明确含 Java，但长中文铺垫产生 33 个
有效查询词，旧评分交集只有 Java，得分 1/33≈0.0303，低于 0.25，实际返回空列表。
失败证据保存后才修改代码。

唯一生产变化：完整相同的非停用 ASCII 标识符（至少三字符）为词面准入提供 0.25
保底，原有更高评分不降低。没有硬编码 Java、案例 ID、答案或新增同义词表/Embedding。
TOP_K、权限、项目范围、版本读取、稳定排序、快照与上下文预算均未改变。

复测 P03 通过；其他已通过案例保持通过。泛化单测使用测试集中没有的 GraphBuilder/
GraphBuilderExtra：完整词匹配，前缀不匹配，无关中文查询为空。

未改善：P01 JDK→Java 没有共享完整词；P02 纯中文“构建工具”→Maven 词面证据不足。
它们继续以 FAIL 保留，未篡改预期或把回答猜对当作召回成功。

## 课程对照材料

本目录 `comparison.md`、UTF-8 BOM 的 `comparison.csv` 均包含老师要求的六列：
案例、评价标准、改进前结果、修改内容、改进后结果、结论，共 12 行。
原始 JSON 保留实际结果明细。评测驱动 JUnit 运行成功不代表 12 个产品案例全通过。

## 局限

- 这是开发集；P03 被用于发现和修复，不能当作未见 holdout 或泛化精度。
- 精确标识符重叠也可能无关；英语常用词、多主题查询的误召回需更大独立数据集评估。
- 两条改写仍失败，不声称解决中文语义理解。
- 结果不是真实 DeepSeek 回答准确率、Embedding 质量或 Token/费用节省；本轮 API 为 0。
- 普通回归不重写冻结原始结果：评测驱动缺显式输出参数时跳过。产品评测失败仍公开保留。

## 少量真实模型抽查计划（未执行）

选择 F01、P01、P03、N01、V01 五例。未来需要新目录、独立持久预算与单独用户授权，
绝不复用或重置 D2 已完成账本；本轮没有放宽 D2 三次上限或准备自动付费运行。

- 最多 5 次真实 HTTP 尝试；禁用自动重试/重定向，30 秒超时，任一错误立即停止，不补测。
- 固定模型、问题和生成参数；纯聊天，无工具；真实 Embedding、LLM 提取及冲突判断 OFF。
- 显式同意 BYTE_LIMITED_INPUT：最终完整 JSON ≤4096 UTF-8 字节，超限拒绝，不截断。
  输出配置 max_tokens=128，五次总目标输出最多 640；输入粗估每次 200–1000 Tokens，非实际 Usage。
- 先由用户确认余额/费率，再单独授权；充值不等于许可。不假设免费额度，不编造人民币费用。
- 逐请求记录条件、选中记忆、上下文哈希、实际回答、状态与 Provider Usage/缓存字段；
  缺失 Usage/扣费数据为 UNKNOWN，估算单独列。
- 检索是否正确与回答是否符合授权事实分别评分；OFF 偶然猜对不能证明 Memory 成功。
- 五次只是改后真实回答抽查，不足以证明真实模型前后改进或统计显著性；成对对照需要另行预算授权。

## 改动范围

生产仅 `LocalCrossSessionMemory.java` 的标识符准入保底。
新增冻结 JSON、`MemoryD3EvaluationTest`、`TechnicalAnchorRetrievalTest` 与本目录课程材料。
原始工作区/结果在 Git 忽略目录；R1/R2、Agent 主循环和历史实验未修改。

## 执行记录与验收

```powershell
# 历史顺序记录：baseline 在生产改动前执行，不能用当前改后源码重跑后冒充旧基线。
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dtest=MemoryD3EvaluationTest' '-Dagent.d3.output=D:\Development\agent-cli\benchmark-runs\memory-d3a-20261101-d3e85bfb' '-Dagent.d3.phase=baseline' test
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dtest=MemoryD3EvaluationTest,DiscoveryOutputPaginationTest,CrossSessionMemoryTest,SemanticMemoryTest' '-Dagent.d3.output=D:\Development\agent-cli\benchmark-runs\memory-d3a-20261101-d3e85bfb' '-Dagent.d3.phase=after' test
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dtest=TechnicalAnchorRetrievalTest,CrossSessionMemoryTest,SemanticMemoryTest,AutoMemoryFlowTest,MemoryConflictTest' test
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

- 基线驱动 1 项成功，产品案例 9/12；复测选择集 27 项成功，产品案例 10/12。
- 最终定向 34 tests，0 failures，0 errors，0 skipped。
- 最终安全回归 718 tests，0 failures，0 errors，15 skipped，BUILD SUCCESS。
  其中 14 项为既有跳过，1 项是未指定新输出目录时显式跳过的人工评测驱动；
  它已在上面两次独立评测中实际执行。既有两类/8 项 loopback HTTP 测试仍未执行，
  未扩大排除列表或弱化任何原有安全断言。
- baseline.json 与测试集冻结哈希复核通过；after.json 初始状态逐项相同。
- 改前和改后生产源码均保存到实验目录，未覆盖原始结果。
- `git diff --check` 通过。真实 API 请求 0，未读取/改动 D2 或个人记忆，未提交/推送。

本材料足以展示“设计测试集→真实发现失败→一项改进→同案复测”的课程挑战，
不是宣称已解决所有 Memory/Agent 问题。
