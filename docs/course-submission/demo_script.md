# Memory 主题演示脚本（建议 7–9 分钟）

**演示目标：** 让观众看见会话内上下文、Session History 和长期 Memory 的差别，并用真实 Exposure 证明有效 Memory 进入了新会话请求。只展示已验证能力；测试结论应准确表述为“隔离副本全量回归通过，16 项 skipped”，不要将其说成真人 UI 或模型质量验收。

## 演示前准备（不计时）

- 使用已验收且包含可展示长期记忆的工作区；在演示机设置 `$env:AGENT_WORKSPACE` 为该工作区的实际路径。提交包不依赖某个开发者本机的绝对路径，也不要求创建新测试工作区。
- 退出其它正在写入该工作区的 Agent；保留该工作区原有数据，不删除或审批旧记录。
- 在终端中隐藏 Key 和私人窗口。确认授权 Memory 中已有一条用于演示的长期偏好；若不存在，不要临场伪造已保存记录或擅自改写 Memory。
- 本机可用 `DEEPSEEK_API_KEY` 启动正式 Daily；正常提问会产生真实模型请求和费用。提前说明并使用简短问题。
- 不在演示中编辑工作区代码。若需要新会话，只创建会话快照，不创建/修改项目文件。

## 0:00–0:50｜介绍问题

说明长期 Memory 不等于保存全部聊天：本项目把同会话上下文、旧 Session 原文检索、跨会话长期偏好分成三层。重点展示第三层如何从用户授权、Agent 提议走到 Java 校验与请求注入。

## 0:50–1:40｜启动正式 Daily

在项目目录执行：

~~~powershell
.\scripts\run-agent.ps1 -Workspace $env:AGENT_WORKSPACE
~~~

指出启动会进行本地离线构建，不发启动模型请求；之后每次正常提问会请求真实 DeepSeek，可能产生费用。只显示 Memory 状态，不把 `/memory list` 的单独数量当成完整验收。

## 1:40–2:40｜会话内上下文

在一个已有会话里围绕刚讨论的计划提出一个简短追问，观察 Agent 使用本会话上下文。说明这只证明当前 Session 的上下文连续性，不是跨会话 Memory。

如果已有会话内容不适合公开，跳过这段，不展示私人聊天。

## 2:40–3:40｜Session History

在 Daily 里用自然语言要求查找某个确实存在于当前工作区旧 Session 的具体讨论，并要求说明来源会话。观察历史搜索工具状态。说明这是按需读取原始旧讨论，与长期记忆无关；搜索为空不能证明该信息从未出现。

选择可公开的旧话题；不要提及或检索其它工作区。

## 3:40–5:00｜Agent-managed Memory 的保存证据

展示已验收的长期偏好记录/其来源和状态。解释此前在正常对话中，DeepSeek 主动调用 `memory_propose`；Java 把来源绑定到真实用户消息，执行授权、质量、敏感内容、作用域、去重和冲突校验后写入共享库。此处展示既有记录即可，不要为重复演示而再次保存或审批。

可指出“Memory 提议工具调用成功”与“Effective 保存成功”是不同结果；发生冲突时仍可能 Pending。

## 5:00–6:30｜新会话自动注入与使用

新建一个 Daily Session，但保持同一工作区。提出不提及旧偏好、也不要求历史搜索的简短聊天问题，例如请给一个 Python 函数的最小示例，并明确“仅在聊天回答，不访问文件”。观察 Agent 是否遵循已保存偏好，确认状态为 COMPLETED 且没有项目文件工具调用。

说明单看回答风格还不是充分证据；下一步只输出新会话快照中的 Exposure 元数据，不打印消息正文：

~~~powershell
$sessionFile = "D:\Development\memory-acceptance-test\.agent-cli\conversations\<session-id>.json"
$snapshot = (Get-Content -Raw -LiteralPath $sessionFile | ConvertFrom-Json).snapshot
$snapshot.crossSessionMemory.exposures | Select-Object profile,startInclusive,endExclusive,memoryIds | ConvertTo-Json -Depth 4
~~~

确认记录含当前 profile、请求区间和对应 Memory ID。这个记录证明选择器把该 ID 纳入请求上下文；它不证明模型在所有场景都必然遵循记忆。不要输出整个会话 JSON。

## 6:30–7:30｜解释 Java 组件

简要映射：`MemoryProposeTool` → `SharedMemoryStore` → `LocalCrossSessionMemory` → `RequestContextSelector` → `CrossSessionState.exposures`。强调 Pending 不会作为 Effective 注入，工作区隔离仍然有效。

## 7:30–8:30｜如实报告限制并结束

指出自然语言更新/遗忘、所有复杂冲突自动处理尚未验收；真实模型可能不主动提议，或安全校验拒绝候选。隔离副本全量 Maven 回归为 1035 tests、0 failures、0 errors、16 skipped；这不等于所有真人界面路径均验收。不要现场修改配置、删除 Memory 或运行破坏性测试。

## 录制后检查

- 视频时长不超过 10 分钟，音频和终端文字可读。
- 画面没有 API Key、账号、真实用户路径以外的私人信息、完整会话正文或其他应用窗口。
- Exposure 中只展示演示所需的 ID/范围；遮挡不必要的原文和会话标识。
- 说明真实 API 请求可能产生费用，并准确区分真人验收、Mock 测试和未验证项。

## 执行反馈补充（如需展示，约 45–60 秒）

用报告中的 `TaskEvidenceTest` 结果解释四个不同状态：文件工具成功只表示写入发生；Python `compile(...)` 或编译器 PASS 只表示语法/编译检查通过；Maven 测试只有真实获批执行且报告存在通过项、无失败/错误时才算功能测试证据；模型文字“已完成”本身不是执行证据。当前没有可引用的最新通用 Python 程序运行演示记录，不要现场宣称已有完整行为验证。实验条件和证据范围见 [memory_execution_experiments.md](memory_execution_experiments.md)。
