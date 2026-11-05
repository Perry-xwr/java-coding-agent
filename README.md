# Java Coding Agent：会话与长期 Memory

一个面向课程项目和本地开发的 Java 17 + Maven Coding Agent。核心课题是让 Agent 在保留完整会话历史的同时，安全地提取、保存并在相关的新会话中使用长期偏好。正式日常入口是 JLine Daily TUI；默认连接 DeepSeek，项目也保留传统 CLI、测试工具和历史实验代码。

本项目是教学与研究原型，不是操作系统沙箱，也不保证模型对所有自然语言意图都判断正确。

## 环境与启动

需要 Java 17、Maven、Windows PowerShell/Windows Terminal，以及一个已配置的 DeepSeek API Key。启动脚本要求环境变量 `DEEPSEEK_API_KEY`，不会把密钥写入项目。请求模型可能产生费用；启动和本地构建本身不发送模型请求。

首次在新机器上需要先取得 Maven 依赖。Daily 启动脚本使用项目本地 `.m2/repository` 并以离线模式编译，因此该目录须已预热。可在联网且信任 Maven 仓库时运行一次：

~~~powershell
Set-Location D:\Development\agent-cli
mvn -f .\pom.xml "-Dmaven.repo.local=.m2/repository" -DskipTests compile
~~~

配置密钥后，从项目目录启动并指定已有工作区：

先通过 Windows 系统环境变量或可信的本地密钥管理方式配置 `DEEPSEEK_API_KEY`；不要把 Key 写入命令记录、脚本或仓库。配置后启动：

~~~powershell
Set-Location D:\Development\agent-cli
.\scripts\run-agent.ps1 -Workspace "D:\Development\your-project"
~~~

脚本执行离线编译和运行 classpath 构建，然后启动 `com.agent.tui.prototype.DailyTuiMain`。不传 `-Workspace` 时使用当前目录。启动不会发模型请求；提交聊天内容才会调用 DeepSeek。不要在源码、日志、截图或课程提交包中放入真实密钥。

常用会话操作包括 `/new`、`/sessions`、`/switch <session-id>`、`/rename`、`/history`。按键与其它命令见 TUI 的底部提示和 `/help`。

## 已实现的 Coding Agent 能力

- DeepSeek Chat Completions / Tool Calling；结构化工具参数经 Java 注册表分发。
- READ 与 CODE/UNIFIED 权限边界；文件定位、读取、创建、精确修改，以及适用时的本地语法/编译验证。
- Maven 测试需要现有 R1 确认流程；不提供任意 Shell。工具和进程仍以当前 Windows 用户权限运行，**不是 OS 级沙箱**。
- 多会话保存、恢复、切换和重命名；会话及历史文件留在所选工作区。
- JLine Daily TUI、流式回答、历史查看、复制等交互能力。

代码修改的人工验收与 Memory 验收是不同证据；构建或语法检查通过不等于功能语义正确。

## 三种不同的记忆/历史

| 能力 | 保存什么 | 如何进入后续请求 | 边界 |
| --- | --- | --- | --- |
| 会话内上下文（M2） | 当前会话的原始消息、摘要/来源事实和任务上下文 | `RequestContextSelector` 为模型请求选择受预算约束的历史副本；原始历史仍保存在会话快照 | 软预算是估算值，不是精确 Token 或费用；工具调用与结果需保持配对 |
| Session History（UX-4） | 具体旧会话中的原始讨论、消息及来源位置 | Agent 在当前工作区调用 `search_sessions` / `read_session_context` 找回明确的旧讨论 | 只读取当前工作区；搜索未命中不证明从未讨论；它不是长期偏好库 |
| Long-term Memory | 经授权、适合跨会话复用的偏好和项目约定 | `memory_propose` 由 Agent 提议；Java 绑定当前用户消息并执行授权、质量、安全、作用域、去重和冲突校验。有效项保存到工作区共享库，新会话按相关性检索并注入 | 不保存所有聊天；不完整、敏感、冲突或无法验证的提议可能被拒绝或留待审核 |

长期 Memory 主链路：

1. 用户在当前工作区明确开启智能记忆授权；Memory 默认不应被理解为对所有工作区开放。
2. 正常对话中，模型可在认为用户表达了稳定偏好时调用 `memory_propose`。它属于 Agent 工具调用流程，不是每轮必跑的独立语义抽取服务。
3. Java 不接受模型提供的来源会话、消息 ID 或工作区路径；来源绑定到真实当前用户消息。Java 再检查授权、完整性、敏感内容、工作区作用域、重复和冲突。
4. 满足安全条件的新提议写入现有 `shared.json`；重复不重复写入，冲突/不确定项不会静默覆盖，可能进入 Pending。Memory 工具失败不能证明已保存。
5. 新请求只检索当前工作区授权的有效记忆。默认最多选 3 条、注入估算上限约 512 tokens，并受会话上下文预算约束。请求上下文将记忆标为低信任数据，不能覆盖当前用户指令。
6. 运行时将实际选中的 Memory ID 写入会话 `crossSessionMemory.exposures`，以便核对“选中并进入请求上下文”的证据。Exposure 证明注入路径，不单独证明模型一定遵守了内容。

工作区会话和长期数据位于该工作区 `.agent-cli/` 下；长期共享记录位于 `.agent-cli/memory/shared.json`。用户授权配置与记忆正文分开保存。不要把真实会话或 Memory 文件提交到课程仓库。

## 授权、作用域与管理

Daily 提供一次性智能记忆确认；可用 `/settings memory status` 查看、`/settings memory off` 关闭，按需用 `/memory list`、`/memory pending` 检查。关闭会阻止之后的跨会话读取/自动提议，不等同于删除已存条目。删除、更新或批准 Pending 属于独立管理操作，不会因一句普通聊天自动发生。

Agent-managed Memory 当前可靠目标是提出和保存新的明确低风险记忆，并在新会话检索使用。自然语言改写旧记忆、自然语言遗忘、无歧义地自动解决复杂冲突、跨工作区共享以及通用语义理解均未作为本次课程验收的已完成能力。语义抽取/Embedding 是可选独立路径，不是自动记忆主链路的必要条件。

## 安全边界

- 每个长期记忆受工作区作用域限制；全局侧栏或 Session 搜索不授予跨工作区正文访问权。
- Pending、Rejected 和关闭授权的记忆不应作为有效长期记忆注入。
- 文件写入仍由当前轮次权限和既有路径检查限制；Memory 存储不是项目文件写入的替代授权。
- Maven 操作需 R1 精确确认；命令执行能力受限，但并非 OS 沙箱。
- `.env`、工作区 `.agent-cli/`、Maven 缓存、`target/` 和评测运行输出不属于课程源码交付内容。`.gitignore` 只是保护措施之一，提交前仍须人工检查暂存区。

## 测试与验收状态

离线测试使用 JUnit、Fake Provider 和 Mock HTTP；测试通过只证明对应断言，不代表真实模型质量。此前一次定向选择集报告为 57 项通过、0 failures/errors；真实 DeepSeek 的 Agent-managed Memory 保存与跨会话检索/注入也已由用户真人验收，Exposure 记录通过核对。CODE 文件创建和连续修改另经真人验收。

**最新全量 Maven 回归通过（隔离副本）。** 2026-11-05 同步四个 legacy Memory 测试适配后，在隔离副本运行 1035 tests、0 failures、0 errors、16 skipped。343 个主源码、156 个测试文件和原始 POM 与当前项目一致；仅 Surefire 临时目录通过测试专用 POM 指向短 `T:` 路径，以支持 Windows 路径限制。Maven 使用离线缓存、模型凭据变量清空，没有真实 API 请求。该结果是隔离副本回归，不是对当前根目录直接运行；完整命令摘要和控制台记录见 [测试状态与证据](docs/course-submission/test_status.md) 及同目录报告文件。

## 课程材料

- [课程报告与 Memory 架构](docs/course-submission/final_project_report.md)
- [五个 AI 辅助开发案例](docs/course-submission/ai_development_cases.md)
- [证据索引](docs/course-submission/ai_development_evidence.md)
- [测试状态](docs/course-submission/test_status.md)
- [不超过 10 分钟的演示脚本](docs/course-submission/demo_script.md)
- [交付白名单与 Git 整理方案](docs/course-submission/git_delivery_plan.md)
- [仍需本人补充的材料](docs/course-submission/missing_evidence.md)
