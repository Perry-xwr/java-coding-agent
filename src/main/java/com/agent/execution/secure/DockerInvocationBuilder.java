package com.agent.execution.secure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Constructs a fixed Docker lifecycle plan; it never launches Docker or pulls an image. */
public final class DockerInvocationBuilder {
    private static final String CONTAINER_ACTION = "/opt/agent/run-maven-test";

    public InvocationPlan build(ExecutionRequest request, ExecutionPolicy policy, StagedWorkspace workspace) {
        policy.validate(request);
        if (workspace == null || !workspace.unchangedSinceApproval()) {
            throw new IllegalArgumentException("Staged workspace is missing or changed");
        }
        String name = containerName(request.requestId());
        List<String> create = new ArrayList<>(List.of("docker", "create", "--pull=never", "--name", name,
                "--label", "com.agent.s1a.request=" + request.requestId(),
                "--network=none", "--read-only", "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true", "--user", policy.containerUser(),
                "--cpus=" + policy.cpuLimit(), "--memory=" + policy.memoryBytes(),
                "--memory-swap=" + policy.memoryBytes(), "--pids-limit=" + policy.processLimit(),
                "--tmpfs", "/workspace:rw,size=" + policy.workspaceTmpfsBytes() + ",mode=1777",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=" + policy.tempTmpfsBytes() + ",mode=1777",
                "--workdir", "/workspace", "--entrypoint", CONTAINER_ACTION,
                policy.imageReference()));
        if (request.testClass() != null) {
            create.add("--test-class");
            create.add(request.testClass());
        }
        if (request.testMethod() != null) {
            create.add("--test-method");
            create.add(request.testMethod());
        }
        String source = workspace.workspaceCopy().toAbsolutePath().normalize().toString();
        if (source.indexOf('\n') >= 0 || source.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Unsafe staging path");
        }
        return new InvocationPlan(name, List.copyOf(create),
                List.of("docker", "cp", source + "/.", name + ":/workspace"),
                List.of("docker", "start", "--attach", name),
                List.of("docker", "stop", "--time", "2", name),
                List.of("docker", "rm", "--force", name),
                List.of("docker", "inspect", "--format", "{{index .Config.Labels \"com.agent.s1a.request\"}}", name),
                request.timeout(), request.maxOutputBytes());
    }

    private static String containerName(UUID id) {
        return "agent-s1a-" + id.toString().replace("-", "");
    }

    public static final class InvocationPlan {
        private final String containerName;
        private final List<String> createArguments;
        private final List<String> copyArguments;
        private final List<String> startArguments;
        private final List<String> stopArguments;
        private final List<String> removeArguments;
        private final List<String> ownershipCheckArguments;
        private final java.time.Duration timeout;
        private final int maxOutputBytes;

        InvocationPlan(String containerName, List<String> createArguments, List<String> copyArguments,
                       List<String> startArguments, List<String> stopArguments, List<String> removeArguments,
                       List<String> ownershipCheckArguments, java.time.Duration timeout, int maxOutputBytes) {
            this.containerName = containerName;
            this.createArguments = List.copyOf(createArguments);
            this.copyArguments = List.copyOf(copyArguments);
            this.startArguments = List.copyOf(startArguments);
            this.stopArguments = List.copyOf(stopArguments);
            this.removeArguments = List.copyOf(removeArguments);
            this.ownershipCheckArguments = List.copyOf(ownershipCheckArguments);
            this.timeout = timeout;
            this.maxOutputBytes = maxOutputBytes;
        }

        public String containerName() { return containerName; }
        public List<String> createArguments() { return createArguments; }
        public List<String> copyArguments() { return copyArguments; }
        public List<String> startArguments() { return startArguments; }
        public List<String> stopArguments() { return stopArguments; }
        public List<String> removeArguments() { return removeArguments; }
        public List<String> ownershipCheckArguments() { return ownershipCheckArguments; }
        public java.time.Duration timeout() { return timeout; }
        public int maxOutputBytes() { return maxOutputBytes; }
    }
}
