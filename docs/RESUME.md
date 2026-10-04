# Resume and Interview Notes

## One-line project description

**中文：** 基于 Java 17 的模型无关 Coding Agent Runtime，提供受限工作区工具、结构化执行反馈、代码验证与有界恢复，并配套可审计的评估框架。

**English:** A Java 17 model-agnostic coding-agent runtime with constrained workspace tools, structured execution feedback, post-edit verification, bounded repair, and an auditable evaluation harness.

## Resume bullets

### 中文

- 设计 Java 17 Coding Agent Runtime，以 `LLMClient` / `AgentEnvironment` 解耦模型与执行环境；使用受限文件工具、typed `ToolResult` 与有界 Agent loop，并以确定性测试覆盖 provider factory、工具 dispatch 和工作区隔离。
- 实现源码修改后的本地验证与结构化 repair 流程；在 12 个重复 DEV 任务的特定 V1.8 评估中，观察到 syntax false-success 从 reread-only 的 7/24 降至 post-edit verification 的 0/24；两组 task success 均为 1/24，结果仅为小样本描述性证据。
- 构建隔离 fixture、独立 evaluator 与 trajectory metrics；在 V1.10 的 12 个任务重复两轮中，验证 Maven 工具在 no-POM 环境 0/92 turns 不暴露、Maven workspace 66/66 turns 暴露；两种模式 task success 均为 10/24，未观察到 mismatch attempt。

### English

- Designed a Java 17 coding-agent runtime around `LLMClient` and `AgentEnvironment`, with constrained workspace tools, typed `ToolResult` observations, and a bounded Agent loop; covered provider construction, dispatch, and workspace isolation with deterministic tests.
- Implemented post-edit verification and structured, bounded repair. In one small repeated 12-task DEV evaluation, observed syntax false-success was 7/24 with reread-only and 0/24 with verification; task success was 1/24 in both modes, so the result is descriptive rather than a general improvement claim.
- Built isolated fixtures, independent evaluators, and trajectory metrics. In V1.10's same 12 tasks repeated twice, Maven advertisement was 0/92 no-POM turns and 66/66 Maven-workspace turns; both modes scored 10/24 and neither produced a live mismatch attempt.

## Two-minute interview explanation

I built this because a coding model can return a plausible answer or even a successful tool-call response while leaving the repository wrong. I wanted to explore the runtime around the model: safe workspace-scoped tools, typed observations, bounded orchestration, verification, recovery, and evaluation against actual files rather than final-answer prose.

The CLI routes to CHAT, READ, or CODE. The Agent depends on `LLMClient` and `AgentEnvironment`; the local environment binds a full tool registry to a confined workspace, filters inapplicable Maven testing, and verifies edits where local tools are available. Verification failures become structured repair context and require a fresh read before another repair mutation. Every run can produce a trajectory for an independent evaluator.

The most important engineering lesson was that mutation success is not correctness. V1.8 showed fewer observed syntax false-successes with verification, but task success did not increase and none of the verifier-failed conditions recovered to PASS. V1.9 added more structured repair guidance; the live recovery counts remained one per condition and did not establish a stable advantage. V1.10 showed the tool-availability mechanism followed its intended environment policy, but no baseline mismatch occurred and task success was equal. The project therefore emphasizes measured boundaries and negative results, not a claim that the Agent is autonomous or production-ready.

## Interview questions and short answers

### Why Java?

Java 17 gives a mature type system, NIO filesystem APIs, process APIs, and Maven-based test workflows that fit this repository-oriented runtime. It also makes the boundary types—tool definitions, typed outcomes, and verification evidence—explicit.

### Why not use LangChain?

The goal was to study and expose the Agent runtime boundaries, not to compare orchestration frameworks. A small implementation keeps provider, environment, tool, recovery, and evaluation behavior directly inspectable. This is a scope choice, not a claim that frameworks are inferior.

### How do you prevent hallucinated tool success?

The runtime records structured tool results and checks workspace state independently. Final prose is not treated as evidence; successful writes are reread and applicable verifiers produce PASS, FAIL, or UNAVAILABLE.

### Why use an independent evaluator?

The model may claim completion despite an unchanged or invalid workspace. The evaluator runs after the Agent in an isolated fixture and checks the actual files and task contract.

### Why does `ToolResult` need typed failures?

An observation such as `TEXT_NOT_FOUND`, `WORKSPACE_VIOLATION`, or `TEST_FAILED` lets the loop respond differently to recoverable argument/edit errors and safety rejections instead of treating every result as an opaque string.

### How is working memory different from conversation history?

Conversation history is the message transcript. Working memory is a bounded, structured session snapshot of task targets and verified tool observations. It is not persistent storage or semantic retrieval.

### Why did planning not help?

It did not show a repeatable advantage in the tested small DEV set and added requests on simple tasks. The result is limited by task composition, model stochasticity, and infrastructure issues in the adaptive run; it does not prove planning is generally harmful.

### How is repair different from verification?

Verification detects a check result. Repair uses a structured diagnostic and fresh file read to guide a later mutation, then requires another verification PASS. The observed evaluations show that detection is more reliable than successful repair.

### Why dynamic tool availability?

Tool applicability can depend on the workspace. The registry keeps the catalog, while the environment filters definitions and rechecks before dispatch. The first production rule is narrow: Maven test availability depends on a safe root POM.

### How would you add a Docker sandbox?

I would implement a separate environment/process-execution adapter with explicit mount, network, CPU, memory, and timeout policy, while keeping the Agent interface stable. Then I would add deterministic isolation/security tests before evaluating it live.

### How could this connect to Agentic RL?

Trajectories and independent task evaluators could form a verifiable reward signal, but current benchmarks are small and do not establish robust reward quality. I would first improve task coverage and evaluator reliability before using them for optimization.
