package com.agent.execution.secure;

/** Execution backend contract. No host-process implementation is supplied by S1-A. */
public interface IsolatedExecutionBackend {
    DockerEnvironmentCheck inspect(ExecutionPolicy policy);

    BackendExecutionResult execute(ExecutionRequest request, ExecutionPolicy policy,
                                   StagedWorkspace workspace) throws Exception;
}
