# 课程大作业报告：具备会话内与跨会话记忆的 Java Coding Agent

整理日期：2026-11-05。本文记录当前实现与可追溯验收，不替代课程要求原件。AI 生成的代码、测试与分析不会写成开发者亲手完成；个人判断和经历仍由开发者本人补充确认。

## 1. 项目目标与技术栈

本项目实现一个 Java 17 + Maven Coding Agent，使用 DeepSeek Tool Calling 与 JLine Daily TUI。课程重点不是“保存所有聊天”，而是区分当前会话上下文、可搜索的原始历史和少量长期偏好，并验证长期记忆可以跨会话进入模型请求。

核心运行路径包括 `RealAgentAdapter`、`Agent`、`ToolRegistry`、`MemoryProposeTool`、`SharedMemoryStore`、`LocalCrossSessionMemory`、`RequestContextSelector` 和 `CrossSessionState`。数据以工作区本地 JSON 持久化，不新增数据库或独立记忆服务。

## 2. 记忆模型与调用链

~~~text
当前会话消息 ──> Agent / DeepSeek Tool Calling ──memory_propose──> Java 来源与权限校验
                                                           │
                         SharedMemoryStore <── Effective / Pending / Rejected
                              │
新会话用户请求 ──> LocalCrossSessionMemory 检索当前工作区授权项
                              │
                              v
RequestContextSelector 选择并预算 ──> Provider messages
                              │
                              v
CrossSessionState.exposures 记录被选中的 Memory ID 和请求区间
~~~

### 会话内上下文

M1 会话管理隔离不同会话的历史和工作状态，并保存/恢复原始会话快照。M2 将完整存储历史与发给模型的请求副本分开：选择器可保留当前任务、近期消息及完整工具证据，并在预算允许时缩减旧上下文。预算为估算软预算；它不是服务端 Token 或费用测量。

### Session History

UX-4 用于找回“某次会话具体讨论了什么”。Agent 可调用 `search_sessions` 和 `read_session_context`，检索范围仅限当前工作区。搜索结果是低信任的历史证据，不能自动升级为新用户事实，也不能扩大为跨工作区读取权限。

### Long-term Memory

Agent-managed Memory 用于复用稳定偏好/约定，而非记录完整对话。模型提出候选，Java 将来源固定为本轮实际用户原文，之后检查授权、工作区作用域、敏感信息、候选完整性、重复和冲突。有效项复用 `SharedMemoryStore` 的现有格式；不确定或冲突的候选不静默覆盖。

新会话在请求前由 `LocalCrossSessionMemory` 检索，`RequestContextSelector` 最多选择 3 项、估算注入上限约 512 tokens，并受请求上下文预算限制。注入块标记为低信任数据。选择结果进入 `SessionContextMetrics`，随后写入 `crossSessionMemory.exposures`，记录 profile、请求消息区间和 Memory ID。

## 3. 安全与功能边界

- Memory 授权与内容分离；Memory OFF 时不应进行自动共享读取/保存。
- 只检索当前工作区授权内容；Session History 与长期库不能跨工作区互相授权。
- Pending/Rejected 项不作为 Effective 长期记忆注入。
- `memory_propose` 仅是专用记忆通道，不赋予修改项目文件的权限。
- CODE 写入受工作区路径、当前轮次意图和已有门控约束；Maven 保留 R1 精确审批。程序不是 OS 级沙箱。
- 已验收的是新偏好提议、持久化、跨会话相关检索/注入与模型应用。自然语言更新/遗忘、自动处理所有复杂冲突、跨工作区记忆和任意自然语言完美提取不在已验收范围。

## 4. 五个 AI 辅助开发迭代

详细案例见 [ai_development_cases.md](ai_development_cases.md)，提示词和证据来源索引见 [ai_development_evidence.md](ai_development_evidence.md)。五例分别覆盖：

1. M1 多会话持久化和隔离。
2. M2 完整存储历史与受控模型上下文。
3. M3 用户授权、工作区作用域和暴露历史隔离。
4. Agent-managed Memory：用受限工具提议，Java 校验并保存。
5. D3-A 冻结检索样例、比较改动前后结果并公开仍未命中的样例。

案例中的需求约束属于用户决策；源码与测试由 AI 工具生成/修改的部分会明确标出。个人实际检查、理解和采纳过程留给开发者本人复核填写。

## 5. 验收与测试结果

Memory ON/OFF、更新、遗忘、冲突审核及执行反馈的实验条件、证据级别和限制，见 [memory_execution_experiments.md](memory_execution_experiments.md)。该实验材料引用现有测试与既有真人验收，不代表本轮重新运行测试。

### 真实模型/真人验收

- 用户确认 Agent 在正常对话中主动调用 `memory_propose`，明确偏好被 Java 保存为有效长期 Memory。
- 2026-11-05 的新会话验收中，Memory ID `7836d287…c493` 出现在该会话 CODE profile 的 `crossSessionMemory.exposures` 中；曝光区间为 `[1,4)`。对应会话回答遵循此前类型注解偏好，记录中未见 `search_sessions` 或 `read_session_context` 调用。
- CODE 文件创建及连续修改另经真人验收。该结论不等同于本次重新运行 Python 项目测试或本轮重新调用模型。

原始工作区会话、完整 Memory 正文和密钥配置不纳入本提交包。以上标识用于本地复核；正式提交前如课程要求截图，请制作脱敏导出并由本人确认。

### 离线与自动化测试

- 最近一次定向回归报告：57 tests，0 failures，0 errors；覆盖意图一致性、CODE 写入安全、Agent-managed Memory、Memory 注入等测试选择集。它不是全量回归。
- D3-A 历史评测报告记录 12 个冻结案例由 9/12 达到 10/12；Hit@3 从 5/8 到 6/8，仍有两个失败查询。详见 `docs/evaluation/memory-d3a-v1/report.md`。这是有限的检索评测，不是通用成功率。
- 2026-11-05 同步四个 legacy Memory 测试适配后，基于当前源码/测试创建的隔离副本全量回归为 **1035 tests、0 failures、0 errors、16 skipped**。本次使用离线 Maven 缓存；测试专用 POM 仅将 Surefire 临时目录设置为短 `T:/` 路径，原始 POM 未改。测试凭据变量清空，未调用真实模型 API。详情见 `test_status.md` 和附带控制台记录。

- 执行证据区分文件工具变更、内置语法/编译校验和实际 Maven 测试结果；Python 内置检查只证明语法可编译，不证明脚本行为正确。通用进程执行结果尚未完整并入统一的任务证据摘要，见实验记录中的限制。

完整状态、证据等级和 Python 验证边界见 [test_status.md](test_status.md)。

## 6. 结论

项目已实现且真人验证了核心 Memory 链路：授权后的 Agent 提议 → Java 校验和持久化 → 新会话按当前工作区检索 → Provider 请求上下文注入 → Exposure 可追溯。它与会话内上下文压缩和原始历史搜索明确分层。隔离副本全量回归通过，但仍有 16 项 skipped；自然语言自动更新/遗忘、高级冲突处理未验收。原始提示词/人工判断材料及演示视频仍需作者本人确认或补录。
