# Security Policy

## Scope

This project is an educational coding-agent prototype, not a hardened sandbox. The application and its child processes run as the current operating-system user. Do not use it on an untrusted workspace or with permissions broader than required.

## Workspace file tools

- File tools receive a configured workspace root and expect workspace-relative paths. Absolute paths and paths escaping the root are rejected.
- Existing and new targets are resolved with normalized and real-path checks. Detected symbolic-link or reparse-point redirections outside the workspace are rejected.
- The registered coding tools include file listing, search and read, `create_file` for a new UTF-8 file without overwrite, and targeted text-edit operations such as `apply_patch` (additional edit strategies depend on the runtime registry). There is no general-purpose file delete tool.
- Text edits require matching current file content; stale or ambiguous patch context fails instead of silently overwriting unrelated content.
- Session, memory, and diagnostic persistence is application-managed data under the workspace's `.agent-cli/` directory. Treat it as private user data and do not publish it.

## Process execution and approval

- The application does not expose an arbitrary shell-command tool to the model.
- `run_maven_test` constructs a fixed Maven `test` goal. Optional selectors are validated; the model cannot supply an arbitrary goal or working directory.
- In the Daily workflow, Maven execution requires the existing explicit approval flow. Denied, unavailable, timed-out, or failed execution must not be represented as a successful test.
- Process execution is bounded by configured time and captured-output limits. These controls reduce risk but do not replace OS isolation.

## Model credentials and network

- The Daily launcher reads `DEEPSEEK_API_KEY` from the process environment and fails if it is absent; it does not automatically load `.env`.
- Other provider entry points use the environment-backed `MODEL_PROVIDER`, `MODEL_BASE_URL`, `MODEL_NAME`, and provider-specific key variables (`GLM_API_KEY`, `DEEPSEEK_API_KEY`, or `MODEL_API_KEY` for the OpenAI-compatible provider).
- Keep real credentials in a trusted local secret store or environment variables. Never commit `.env`, print credentials, or include them in logs, screenshots, session exports, or public test fixtures.
- Model requests are sent to the configured provider and may incur charges. Default automated tests use fakes or mock HTTP; live-provider tests are separate and opt-in.

## Reporting

For a suspected vulnerability, do not include credentials, private session data, or memory contents in a public issue. Use GitHub's private vulnerability reporting for this repository when available; otherwise contact the repository owner privately with a concise reproduction and affected version.
