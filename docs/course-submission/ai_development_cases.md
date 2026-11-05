# 五个 AI 辅助开发案例

整理日期：2026-11-05。每个案例区分用户需求、AI 工具执行和开发者本人核验。提示词是基于对话的摘要，不冒充平台导出的逐字原文；若开发者没有亲自运行某项测试，不能把它写成个人手工操作。

## 案例 A：M1 多会话持久化与隔离

- **背景与目标：** Coding Agent 需要恢复多个独立会话，不能让会话 A 的消息、工具历史或工作状态进入会话 B。
- **给 AI 的要求摘要：** 复用现有 CLI/Agent，不重写主循环；实现创建、列出、切换、重命名和历史恢复；会话 ID 与用户可见标题分离；重启可恢复。
- **AI 实施内容：** 引入会话管理器、快照和本地 JSON Store，保留 profile 历史；工具调用作为历史数据保存，不在恢复时重放。
- **检查点：** `ManagedConversationTest` 的跨会话切换/重启请求断言，以及缺失/损坏快照和 READ 权限测试。阶段报告中的 638-test 数字是历史阶段记录，不是当前全量结果。
- **开发者决策与待补：** 用户要求复用既有 Agent 并隔离会话。开发者需补充自己实际检查了哪次 A/B 切换或重启，以及个人采纳/修改判断；没有证据时不要声称亲手运行。
- **证据入口：** [M1 设计](../memory_m1_sessions.md)、[Manager](../../src/main/java/com/agent/CliConversationManager.java)、[测试](../../src/test/java/com/agent/ManagedConversationTest.java)。

## 案例 B：M2 完整历史与请求上下文分离

- **背景与目标：** 长会话会扩大请求；减少旧上下文时不能删除原始聊天，也不能丢失工具调用/结果与当前任务约束。
- **给 AI 的要求摘要：** 不按固定字符数粗裁；使用可追溯的摘要/事实和软预算；不为每次压缩额外调用付费模型。
- **AI 实施内容：** 增加来源可核对的会话摘要和 `RequestContextSelector`，仅构造 Provider 请求副本；原始历史继续保存在 Session。
- **检查点：** `SessionContextMemoryTest` 覆盖历史来源、工具配对、恢复和上下文选择。历史合成场景的 42720→5881 是估算量，不是 DeepSeek 实际计费 Token。
- **开发者决策与待补：** 用户要求保留完整存储和工具证据。请本人补充是否检查过压缩前后的具体上下文、对软预算的理解和接受理由。
- **证据入口：** [M2 设计](../memory_m2_context.md)、[选择器](../../src/main/java/com/agent/agent/RequestContextSelector.java)、[测试](../../src/test/java/com/agent/memory/SessionContextMemoryTest.java)。

## 案例 C：跨会话 Memory 的授权、隔离与回流保护

- **背景与目标：** 长期偏好需要在新会话复用，但关闭后不应通过历史回显继续进入模型，也不能读到其他工作区内容。
- **给 AI 的要求摘要：** 用户明确授权；共享内容作为低信任数据；按工作区隔离；用真实请求消息断言 OFF/ON 行为。
- **AI 实施内容：** `SharedMemoryStore` 持久化、`LocalCrossSessionMemory` 负责范围和检索、`CrossSessionState` 记录暴露轮次，选择器把记忆作为请求级上下文。
- **检查点：** `CrossSessionMemoryTest`、`CrossSessionMemoryInjectionTest` 覆盖授权、工作区、Pending/Rejected 与请求内容。Fake Provider 的故意回显是安全测试，不是真实泄漏事故。
- **开发者决策与待补：** 用户明确要求 OFF 后不再注入并固定工作区。本人是否亲自检查 OFF 请求或确认隐私与连续性取舍，待填写。
- **证据入口：** [M3 设计](../memory_m3_cross_session.md)、[状态](../../src/main/java/com/agent/memory/CrossSessionState.java)、[跨会话测试](../../src/test/java/com/agent/CrossSessionMemoryInjectionTest.java)。

## 案例 D：Agent-managed Memory 的真实闭环

- **背景与目标：** 旧语义提取依赖单独模型抽取和严格证据匹配，复杂中文偏好容易拒绝。目标改为模型在正常任务中主动提议，Java 仍是最终校验者。
- **给 AI 的要求摘要：** 不建立新数据库；新增受限的 `memory_propose`；来源必须由 Java 绑定当前用户消息；不让 Memory 工具绕过文件写权限；完成离线及真人跨会话验收。
- **AI 实施内容：** `RealAgentAdapter` 在授权条件满足时注册专用工具；`MemoryProposeTool` 绑定当前轮用户原文和工作区；`SharedMemoryStore` 复用原有效格式；新会话经 `LocalCrossSessionMemory` 与 `RequestContextSelector` 检索/注入。
- **真人验收事实：** 开发者报告 DeepSeek 主动调用工具并成功保存长期偏好。2026-11-05 新会话记录中，Memory ID `7836d287…c493` 出现在 CODE profile 的 Exposure；会话回答应用了类型注解偏好，保存记录未发现 Session History 搜索工具调用。Exposure 代表请求选择器实际选中的记忆 ID 和请求区间；它不是原始网络报文，也不证明所有回答永远服从记忆。
- **离线检查：** `AgentManagedMemoryE2ETest` 覆盖提议、拒绝/重复/冲突和重启；`CrossSessionMemoryInjectionTest` 检查 Provider 消息和 Exposure。最近定向选择集中的这两组共 6 项通过。真实原始会话、Memory 正文不复制到提交包。
- **开发者决策与待补：** 用户明确选择 Agent-managed 方案，并完成真人验收。课程提交前请本人确认展示内容已脱敏，以及其对 Pending/冲突行为的理解。
- **证据入口：** [工具](../../src/main/java/com/agent/tool/MemoryProposeTool.java)、[共享库](../../src/main/java/com/agent/SharedMemoryStore.java)、[请求选择器](../../src/main/java/com/agent/agent/RequestContextSelector.java)、[端到端测试](../../src/test/java/com/agent/AgentManagedMemoryE2ETest.java)、[注入测试](../../src/test/java/com/agent/CrossSessionMemoryInjectionTest.java)。

## 案例 E：冻结检索评测后做最小调整

- **背景与目标：** 不能只用手工印象判断跨会话检索，需固定查询和答案依据，再比较改前改后。
- **给 AI 的要求摘要：** 先保存 12 个查询的固定评测集和基线；只针对有证据的失败改一个点；如实报告没改善的案例。
- **AI 实施内容：** 新建 D3-A 评测数据/比较材料，对 `LocalCrossSessionMemory` 的技术锚点匹配做局部调整。
- **历史报告结果：** 12 案例通过数 9/12→10/12，Hit@3 5/8→6/8，MRR 0.625→0.750；JDK→Java 和“构建工具”→Maven 两类改写仍未命中。不能把这些数解释为一般化成功率。
- **可复现性缺口：** 摘要在 `docs/evaluation/memory-d3a-v1/`；原始 `benchmark-runs/` 被忽略且不在提交白名单。若课程要求原始文件，需先检查、脱敏并选择性复制，不能将整个运行目录提交。
- **开发者决策与待补：** 用户要求控制变量、同案复测和公开失败；请本人补充是否查看了 P03 等原始行、是否认同误召回风险。
- **证据入口：** [报告](../evaluation/memory-d3a-v1/report.md)、[对照表](../evaluation/memory-d3a-v1/comparison.md)、[测试驱动](../../src/test/java/com/agent/MemoryD3EvaluationTest.java)。