# 测试与真实验收状态

整理日期：2026-11-05。全量测试报告来自隔离副本；本轮同步的只有四个测试适配和课程材料，没有修改产品源码。

## 当前可报告结果

Memory 管理和执行反馈实验对照见 [memory_execution_experiments.md](memory_execution_experiments.md)。其中列出的测试方法是源码覆盖证据；本轮未执行测试，不能据此声称刚刚通过。

| 验证项 | 状态 | 证据范围 |
| --- | --- | --- |
| Agent-managed Memory 自动提议与保存 | 真人验收 PASS（开发者确认） | DeepSeek 在正常对话中调用 `memory_propose`；Java 校验后有效 Memory 持久化。未把原始私人会话复制进提交包 |
| 新会话自动检索/注入与 Exposure | 真人验收 PASS | 2026-11-05 CODE 会话 Exposure 记录到有效 Memory ID；会话无 `search_sessions` / `read_session_context` 调用；相关回答应用了偏好 |
| CODE 文件创建、连续修改 | 真人验收 PASS（开发者确认） | 用户报告创建及连续修改已通过；本轮未重新修改或执行工作区代码 |
| 只读聊天与 CODE 意图切换 | 定向 E2E PASS | 此前选择集报告 57 tests、0 failures、0 errors；本轮另有 40 项 Memory 定向回归通过；覆盖聊天只读、创建/修改、Memory 声明及完成保护 |
| Agent-managed Memory Mock | 定向 PASS | `AgentManagedMemoryE2ETest` 4 项、`CrossSessionMemoryInjectionTest` 2 项在该定向运行中通过；Mock 不代表真实模型会主动选工具 |
| 全量 Maven 回归 | **PASS（隔离副本）** | 1035 tests、0 failures、0 errors、16 skipped；原项目主源码、测试和 POM 逐文件核对一致，细节见下文与附带控制台记录 |
| Python 语法/行为验证 | 部分/不作为本报告 PASS | 真人验收确认文件创建/修改；目前没有本轮新鲜的、完整覆盖目标文件的语法与执行报告。不要宣称所有 Python 代码已运行正确 |
| D3-A 检索小型评测 | 历史结果，有限通过 | 12 个固定案例 9/12→10/12，Hit@3 5/8→6/8，MRR .625→.750；仍有失败，不能推广为总体准确率 |

## 最新全量回归记录

- 时间：2026-11-05 20:16（Asia/Shanghai）；命令：`mvn -o -f pom-isolated-test.xml -Dmaven.repo.local=.m2/repository test`。
- 结果：1035 tests，0 failures，0 errors，16 skipped，`BUILD SUCCESS`。没有真实模型 API 请求；Provider 凭据环境变量在测试进程中清空，Maven 离线运行，HTTP 测试使用本机 Mock。
- 隔离副本由同步后的项目文件创建：343 个 `src/main/java` 与 156 个 `src/test/java` 文件逐文件 SHA-256 比对均一致，原始 `pom.xml` 一致；没有复制 `.env`、`.git`、`.agent-cli` 或原 `target`。Maven 离线缓存来自此前验证副本。
- 测试专用 `pom-isolated-test.xml` 相对原始 POM 只将 Surefire 的 `java.io.tmpdir`、`TEMP`、`TMP` 从构建目录改为 `T:/`；运行时短盘符映射到隔离副本并在结束后移除。原始 `pom.xml` 未改。
- 同步后在原项目上运行的定向 Memory 回归：40 tests、0 failures、0 errors、0 skipped；包含四个 legacy 测试类、`AgentManagedMemoryE2ETest` 与 `CrossSessionMemoryInjectionTest`。
- 先前一次受限沙箱运行出现 8 个 loopback mock 连接错误和 1 个 Windows 长路径断言失败；使用本机 loopback 权限和短路径再次进行完整回归后通过。前次环境受限结果不与本次报告混算。
- 完整控制台和聚合摘要随本材料提供：`full-test-final-console.txt`、`full-test-final-summary.txt`。Surefire 原始 XML 保留在隔离副本的 `target/surefire-reports/`，不纳入课程 ZIP。

四个旧提取路径测试明确设置 `agent.memory.agentManaged=false`，并在 `AfterEach` 恢复旧属性；断言均保留，没有新增跳过或削弱安全检查。当前 Agent-managed 流程仍由其自己的 E2E 和注入测试覆盖。

## Python 文件修改与验证边界

- 文件存在、内容被读取、修改结果被重新读取，分别是不同层级证据。
- Python 语法检查只证明语法；脚本执行只证明该输入/环境下的行为；目标需求还需具体断言或人工核对。
- 工具在适用环境可运行本地 `py_compile`/语法检查，但没有本报告可引用的最新逐文件执行结果。因此课程演示只展示已经真人确认的创建/编辑能力；除非本人现场运行对应脚本并保留输出，不说“完整运行测试通过”。
- Windows Terminal 鼠标、剪贴板、IME、粘贴等是独立手工验收面，不由 Mock TUI 测试替代。

## 报告边界

这份结果证明当前源码/测试快照在指定隔离环境下通过 Maven 全量测试；16 项仍为 skipped。它不证明 Windows 终端的真人 UI 行为，也不证明真实 DeepSeek 输出质量在每种输入下都稳定。Python 语法检查和实际功能运行是不同证据。
