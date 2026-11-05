# Git 交付白名单与整理方案

核对日期：2026-11-05。整理起始 HEAD：`5b846db8f01d5cee34d0689726db009c4924a40e`。本次只将课程交付所需文件按白名单整理；历史审计对话、真实验收数据和本机运行产物不纳入公开范围。实际本地提交 SHA 和剩余未提交文件以项目 Git 状态/最终交付报告为准；本次不推送。

## 建议交付白名单

### 项目可构建内容

- `pom.xml`、`src/main/java/`、`src/test/java/`：应用源代码和对应回归测试。源码/测试目录中包括多个历史阶段和工具模块，Maven 项目需要保持完整树；提交前仍要检查差异，不要只按目录名推断每个新文件都正确。
- `README.md`、`SPEC.md`、`LICENSE`、`SECURITY.md`、`PUBLIC_RELEASE_AUDIT.md`、`.gitignore`。
- `scripts/run-agent.ps1`：正式 Daily 启动入口。

### 课程材料

- `docs/course-submission/final_project_report.md`
- `docs/course-submission/ai_development_cases.md`
- `docs/course-submission/ai_development_evidence.md`
- `docs/course-submission/test_status.md`
- `docs/course-submission/memory_execution_experiments.md`
- `docs/course-submission/full-test-final-summary.txt` 与 `full-test-final-console.txt`（隔离副本的离线全量测试证据）
- `docs/course-submission/demo_script.md`

课程包包含 `docs/course-submission/` 全部材料，包括 `missing_evidence.md` 和本文件；若课程通知不要求提交前检查清单，作者可在最终上传前决定是否从 ZIP 中排除这两份过程文档。公开项目保留它们作为透明的交付说明。

### 评测与设计材料

- `docs/evaluation/memory-d3a-v1/` 和 `benchmark/memory-d3a-v1/cases.json`：本包纳入固定小型评测材料，并明确样本规模和失败项。
- `docs/DESIGN.md`、`docs/architecture.md`、M1–M4/M6 Memory 设计文档：本包纳入，帮助复核课程报告中的架构说明。
- `CLAUDE.md`、`docs/audit/`、`docs/archive/`、`demo/python/n_queens.py`：未纳入课程包；前两类含工作指令/阶段审计材料，示例 Python 文件不是 Agent 构建所必需。

## 明确排除

- `.env`、任何 API Key/Token/密码、个人授权配置。
- `.agent-cli/` 下的会话、Memory、工作区登记、回收站和诊断状态。
- `.m2/`、`target/`、`benchmark-runs/`、`*.log`、IDE 缓存及生成产物。
- 完整真人 Session JSON、Memory JSON、未经脱敏的模型请求/响应、截图/录屏中的密钥和私人窗口。
- `scripts/` 下其它真实 API 探测/验收脚本：未逐个审阅前不整目录加入；它们不是正式启动所必需。

已检查的根 `.env` 被忽略，变量名是 `GLM_API_KEY`，未读取值且不在备份中。源代码检索命中的 Key/Bearer 样式为测试 canary/合成值；这不是对完整 Git 历史对象的全量秘密扫描。提交前仍须再次检查暂存区和历史。

## `.gitignore` 结论

现有规则已忽略 `.env`、`.m2/`、`target/`、`benchmark-runs/`、日志和部分 `.agent-cli` 子目录。检查发现 `catalog`、`trash` 等运行目录未被覆盖；本轮已在 `.gitignore` 增加统一的 `.agent-cli/` 规则。忽略规则不会删除文件，也不会自动从索引中移除已经被 Git 跟踪的文件；后续应检查 `git ls-files .agent-cli` 确认没有运行数据已被跟踪。

## 安全提交与推送边界

1. 先审阅每个白名单目录的 `git diff` 与新增文件；确认 AI 生成材料、测试假 Key 和 demo 文件适合提交。
2. 手动按白名单路径逐项暂存，**不要使用 `git add .`、`git add -A` 或目录外通配符**。先将核心源码/测试与课程文档分成两个可审阅的提交单元。
3. 暂存后运行 `git diff --cached --check`、`git diff --cached --stat` 和 `git diff --cached --name-only`；确认没有 `.env`、`.agent-cli`、`target`、`.m2`、`benchmark-runs`、真实会话或个人 Memory。
4. 对暂存区再次运行秘密扫描并逐段审阅。若发现任一凭据，停止提交、撤出该文件并按密钥泄露流程处理；不要把真实 Key 贴到聊天中。
5. 只在当前课程分支创建本地提交；提交后核对 SHA、父提交和 `git status`。本次不推送公开仓库，待作者查看拟推送清单和秘密扫描结果后再决定。
6. 记录隔离副本全量回归为 1035 tests、0 failures、0 errors、16 skipped，并说明测试专用临时目录设置；保留 skipped 数量，不作“原工作树直接测试”或“全功能真人验收”表述。
