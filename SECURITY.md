# Security Policy

## Workspace policy

- File tools are confined to their configured workspace root.
- Tool paths must be workspace-relative; absolute paths are rejected, including absolute paths that point inside the workspace.
- Normalized paths that leave the workspace through `..` traversal are rejected.
- Existing targets are resolved with `Path.toRealPath()`. A symbolic link, junction, or other Java NIO-detectable redirection whose final target is outside the real workspace root is rejected.
- If an existing path cannot be resolved reliably, access fails closed rather than bypassing validation.

This policy applies to `list_files`, `read_file`, `search_code`, `apply_patch`, and the experimental `replace_lines` tool. Future file-creation operations require a separate creation-path policy and are not covered by the current resolver.

## Write policy

- `apply_patch` is the V1 write-capable tool. The experimental `replace_lines` tool is available only in its explicit strategy registry; there is no arbitrary `write_file` tool.
- Only existing regular UTF-8 files inside the configured workspace may be patched.
- Patch paths must be workspace-relative and pass the same normalized and real-path checks as read tools.
- A patch is accepted only when `oldText` has exactly one match. Missing or ambiguous matches leave the original file unchanged.
- A line replacement additionally requires a valid line range and exact `expectedText` from the current file. Stale context leaves the file unchanged.
- Updated content is written to a temporary file in the target directory and moved over the original with atomic replacement when the filesystem supports it. A same-filesystem safe replacement is used as fallback.
- The runtime cannot create arbitrary target files, delete files, or rename/move files.
- Symbolic-link or reparse-point targets outside the workspace are rejected before writing.

## Process policy

- There is no generic shell or arbitrary-command tool.
- `run_maven_test` constructs a fixed `ProcessBuilder` argument list; it never accepts a command or Maven goal from the model.
- The only allowed Maven goal is `test`, optionally restricted to a validated Java test class and method.
- The process working directory is fixed to the configured workspace root and cannot be supplied by the model.
- Test selectors accept only Java identifier/package characters; shell and control characters are rejected.
- Maven execution has a 120-second timeout. Timed-out processes and descendants are forcibly terminated.
- Combined stdout/stderr capture is limited to 100 KiB and reports whether truncation occurred.
- Test failures are recoverable typed observations, not fatal Agent termination.
- The Maven local repository path is host-configured and is not model-controlled.

## Test policy

- `mvn test` runs the deterministic offline suite and does not call the GLM provider, even when `GLM_API_KEY` is present.
- A live GLM smoke test runs only when explicitly enabled with `mvn test -Pglm-integration` and `GLM_API_KEY` is available.
- If the integration profile is enabled without `GLM_API_KEY`, the live test is skipped.
- The real Phase 3 Maven fixture test is opt-in through the `phase3.integration` system property; ordinary tests use an injected deterministic process runner.
- API keys must remain in environment variables and must not be printed or committed.

## Credential policy

- `GLM_API_KEY` is read from the process environment; it is never hard-coded in the repository.
- `.env` is ignored by Git and must not be committed.
- Trajectories, evaluations, reports, and debug output must not contain API keys, Authorization headers, or bearer tokens.
- `GLM_DEBUG` controls HTTP status logging only and must not expose request credentials.
