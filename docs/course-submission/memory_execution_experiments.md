# Memory 与执行反馈实验记录

整理日期：2026-11-05。本文使用现有源码、自动化测试和已记录的真人验收事实整理；本轮没有运行测试、调用模型或读取真实工作区数据。测试源码说明测试覆盖了什么，不代表本轮重新执行通过。

## 证据标记

- **真人验收**：此前由开发者报告并确认的正式 DeepSeek/Windows 运行结果。
- **Mock E2E**：隔离测试将真实 Daily/CLI 控制器、工具分发或 Provider 请求捕获接起来，但 Provider 是模拟实现。
- **单元/组件测试**：验证局部实现和状态判定，不等于用户端到端验收。
- **未验证**：当前材料没有足够证据，不从代码存在推断运行成功。

## 实验对照

| 实验 | 输入条件与预期 | 实际证据 | 结论 | 适用范围与限制 |
| --- | --- | --- | --- | --- |
| Memory ON/OFF | 同一隔离工作区预置一条有效记忆，对相同问题分别在 ON、OFF 状态发送；预期 ON 请求含记忆块，OFF 请求不含该块。 | `MemoryConsentTest` 用相同的“解释 Java 版本，不修改文件”请求，断言开启时捕获的 Provider 请求含 canary 记忆，关闭后请求不含它。`CrossSessionMemoryInjectionTest` 另覆盖关闭状态不检索、其他工作区不命中、Pending/Rejected 不进入有效库。 | **Mock E2E 覆盖请求上下文差异。**没有据此声称 ON/OFF 的自然语言答案必然不同。 | 证明的是检索/注入开关生效，不是模型回答质量对照。真人跨会话注入主链路此前已单独验收 PASS。 |
| 更新已有记忆 | 已有“Java 17”记忆，显式更新为“Java 21”；预期稳定 ID 保留、旧内容留作版本记录，新请求只使用当前内容。 | `MemoryManagementTest.updateRetainsIdentityProvenanceAndCreationButAdvancesUpdatedTime` 检查 ID、来源、创建时间及更新时间；`fakeLlmE2eUsesUpdatedContentThenNoDeletedContentIncludingEchoAndRestart` 检查更新后的新请求包含 21、不含 17。`MemoryConflictTest.replacementIsAtomicVersionedAndStaleSafe` 检查旧版本保留、过期版本拒绝覆盖。 | **Mock E2E 与存储测试覆盖。** | 通过 `/memory update` 或显式 Pending 审核替换；未验收自然语言直接改写长期记忆。修订历史最多保留 20 版。 |
| 遗忘已有记忆 | 对指定 ID 执行 `/memory forget`；预期后续请求不再注入该项。 | `MemoryManagementTest.forgetIsDurableAndAffectsOnlyExactId` 检查精确删除；`fakeLlmE2e...` 检查遗忘后新请求没有旧记忆块，并检查原会话历史仍存在。 | **Mock E2E 覆盖后续不注入。** | 遗忘只从共享长期库移除，不擦除原会话、旧消息或已经发送给模型的历史上下文。 |
| 重复、冲突与 Pending | 重复项应去重；可能冲突的提议不应覆盖旧项，而应进入 Pending；明确审核后才替换并保留旧版本。 | `MemoryConflictTest.deterministicRelationsAreConservative` 覆盖重复/冲突分类；`fakeLlmConversationReviewReplaceAndRejectBranch` 覆盖查看候选、拒绝或替换以及新会话只见当前版本；`CrossSessionMemoryInjectionTest` 验证 Pending/Rejected 不注入。 | **Mock 端到端和组件测试基本覆盖。** | 冲突分类偏保守；不等于所有自然语言修订都能自动正确识别。没有自动过期处理。 |
| 执行反馈 | 分别区分模型回答/建议、文件工具写入、语法/编译校验、测试执行结果和权限拒绝。 | `TaskEvidenceTest` 覆盖写入成功但未功能验证、写入失败、Python 语法 PASS 不是功能 PASS、Maven 测试 PASS/FAIL、审批但未执行、全部跳过不算通过等。`BuiltInCodeVerifiers` 对 Python 使用 `compile(source, ..., 'exec')`，只检查语法；`RunMavenTestTool` 只有实际获批执行、报告有通过测试且失败/错误为零时才报告测试通过。 | **写入、语法/编译、Maven 测试三层可区分；通用程序运行反馈仅部分覆盖。** | `TaskEvidence` 有 `CommandExecutionEvidence` 类型，但从 Agent 轨迹生成汇总证据时目前没有填充通用命令执行事件。不能把 Python 语法检查说成程序运行成功，也不能把模型声称成功说成工具证据。 |

## 可复现的既有测试入口

- Memory 开关与持久化管理：`src/test/java/com/agent/MemoryConsentTest.java`、`src/test/java/com/agent/MemoryManagementTest.java`。
- 冲突、版本和候选审核：`src/test/java/com/agent/MemoryConflictTest.java`。
- 新会话 Provider 请求及 Exposure：`src/test/java/com/agent/CrossSessionMemoryInjectionTest.java`。
- 写入/验证/测试状态区分：`src/test/java/com/agent/agent/TaskEvidenceTest.java`、`src/test/java/com/agent/environment/verification/PostEditVerificationServiceTest.java`。

这些是现有测试源码中的覆盖范围，不是本轮新运行结果。课程材料中可用它们说明设计和离线验证路径；如需报告“本次通过”，应引用同一次测试运行的 Surefire 报告和命令记录。

## 总体结论

长期 Memory 的查看、显式更新、遗忘、冲突审核、授权开关和跨会话注入已有实现与 Mock 覆盖；保存到新会话请求的主链路此前有真人验收证据。执行反馈能避免把写入或语法检查夸大为功能测试通过。尚未完整证明任意 `run_process` 命令的批准、实际启动、退出码均进入统一的 `/task-evidence` 汇总；这是执行反馈呈现层的已知缺口。2026-11-05 隔离副本全量 Maven 回归通过：1035 tests、0 failures、0 errors、16 skipped；隔离细节见 `test_status.md`。
