# AI 辅助开发证据索引

整理日期：2026-11-05。本文给出可复核的源码、测试和验收依据，并区分证据强度。

## 证据规则

- **用户需求记录**：本对话中可访问的开发要求；此文的提示词均为摘要，不是逐字平台导出。
- **源码**：证明当前存在的实现，不单独证明历史上何时写成或某次运行成功。
- **测试源码**：证明测试断言设计；只有对应 Surefire 报告/运行记录才能证明测试执行。
- **Mock/离线测试**：证明对应 Java 调用路径在模拟条件下通过，不证明真实模型会选择相同工具或回答质量。
- **真人 DeepSeek 验收**：以开发者报告和既有本地记录为依据；本课程包不复制 API 凭据、完整私人会话或 Memory 正文。
- **开发者个人判断**：由开发者本人补充，不将 AI 的代码分析或测试执行描述成自己的手工操作。

## 五个案例定位

| 案例 | 源码/测试 | 可支持的结论 | 限制 |
| --- | --- | --- | --- |
| A：M1 会话隔离 | `CliConversationManager.java`、`CliSessionStore.java`、`ManagedConversationTest.java` | 当前会话持久化/切换隔离有实现和自动断言 | M1 阶段 638 项为历史阶段报告，不是当前全量运行 |
| B：M2 请求上下文 | `RequestContextSelector.java`、`SessionContextMemoryTest.java` | Provider 请求可以从完整存储历史构造受控副本 | 估算 Token 不等于 Provider Usage；历史摘要数字是合成场景 |
| C：授权与作用域 | `CrossSessionState.java`、`LocalCrossSessionMemory.java`、`CrossSessionMemoryInjectionTest.java` | 有工作区作用域、授权与注入断言 | Mock 不证明模型行为；Fake 回显是测试，不是事故 |
| D：Agent-managed Memory | `MemoryProposeTool.java`、`SharedMemoryStore.java`、`AgentManagedMemoryE2ETest.java` | 工具提议经 Java 校验；离线新会话注入有测试 | 真人行为需结合实际验收记录，不能只引用测试源码 |
| E：检索评测 | `benchmark/memory-d3a-v1/cases.json`、`MemoryD3EvaluationTest.java`、`docs/evaluation/memory-d3a-v1/` | 有固定小型评测与历史 before/after 汇总 | 原始运行 JSON 位于忽略目录，不自动进入 Git；样本规模很小 |

详述见 [五个开发案例](ai_development_cases.md)。

## 最新真人 Memory 验收

2026-11-05 的新会话前缀 `c0e9ce` 的快照中，CODE profile 的 `crossSessionMemory.exposures` 包含 Memory ID 前缀 `7836d287…c493`，范围 `[1,4)`。会话中没有 `search_sessions` 或 `read_session_context` 记录；回答实际遵循了此前的 Python 类型注解偏好。该证据与源码路径一致：`RealAgentAdapter` 检索授权记忆，`RequestContextSelector` 生成含记忆的请求副本并记录所选 ID，适配器将 `SessionContextMetrics` 写为 Exposure。

结论：这次“保存 → 新会话检索 → 注入”的真人主链路 PASS。当前交付包不包含该工作区完整会话、`shared.json` 或原始 Provider 请求正文。若老师要求查看证据，请提供由本人审阅的脱敏截图/摘要，保留 Memory ID/Exposure 证明但遮掉密钥、私人内容和其它会话数据。

源码入口：[RealAgentAdapter](../../src/main/java/com/agent/RealAgentAdapter.java)、[RequestContextSelector](../../src/main/java/com/agent/agent/RequestContextSelector.java)、[Exposure 状态](../../src/main/java/com/agent/memory/CrossSessionState.java)。

## 测试记录使用规则

- **最近定向选择集**：57 tests、0 failures、0 errors；包括 CODE 意图一致性、写入保护、Daily、Agent-managed Memory 和注入测试。它不等于全量回归。
- **最新完整回归**：2026-11-05 隔离副本 1035 tests、0 failures、0 errors、16 skipped。主源码、测试和原始 POM 与当前项目核对一致；测试专用 POM 仅改变 Surefire 临时目录设置。完整控制台和摘要随课程材料提供，详见 [test_status.md](test_status.md)。
- **M1/M2/M3/M6 阶段数字及 D3-A 结果**：可作带日期的历史开发记录，不能替代当前代码/全量运行状态。其证据详细边界见 [test_status.md](test_status.md)。
- 课程提交时应附上可审阅的当前报告副本，注明测试命令、日期、测试总数、失败、错误和跳过数量；若暂时没有单文件原始控制台汇总，直接注明来源与限制，不手工拼造。

## 暂不作为提交附件的内容

- `.env`、真实 API Key、个人 Memory、会话正文、工作区 `.agent-cli/`。
- `target/`、`benchmark-runs/`、`.m2/` 原目录。必要评测证据应逐项脱敏后另存。
- 真实 API 的原始请求头/完整响应，以及未遮挡账号或个人窗口的截图。
