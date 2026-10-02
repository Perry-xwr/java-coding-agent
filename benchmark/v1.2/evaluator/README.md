# V1.2 Evaluator Boundary

The `initialFixture` map in `../manifest.json` is the complete file set made available to an Agent for a task. Evaluator-only assertions, including expected content, required trajectory facts, and Maven hidden tests, belong outside that workspace and are applied only after an Agent run finishes.

The protocol labels these assertions `HIDDEN_*` to mean **hidden from the runtime workspace**. Because this directory is versioned in the public repository, it is not a secret benchmark. This is intentionally described as *runtime-hidden / protocol-held-out*.

The Phase 1 manifest freezes the evaluator kind and success criteria. A later offline executor must implement each evaluator against these declared criteria without changing V1 (`v0.1`) evaluator code or historical data.
