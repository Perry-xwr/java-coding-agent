package com.agent.llm;

import com.agent.diagnostics.LiveTestDiagnostics;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory per-turn aggregation for provider requests; no prompts or tool contents are retained here. */
public final class ModelRequestTelemetry {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<TaskCollector> ACTIVE = new ThreadLocal<>();

    private ModelRequestTelemetry() { }

    public static void beginTask(String runId, String profile, long agentTurn) {
        ACTIVE.set(new TaskCollector(runId, profile, agentTurn));
    }

    public static RequestHandle beginRequest(String provider, String model, String endpoint,
                                             List<Message> messages, List<ToolDefinition> tools,
                                             long payloadBytes) {
        TaskCollector task = ACTIVE.get();
        long sequence = SEQUENCE.incrementAndGet();
        RequestContextMetrics metrics = RequestContextMetrics.of(messages, tools, payloadBytes);
        String safeEndpoint = safeEndpoint(endpoint);
        String runId = task == null ? "" : task.runId;
        String profile = task == null ? "UNSCOPED" : task.profile;
        long turn = task == null ? 0 : task.agentTurn;
        ModelRequestUsage started = new ModelRequestUsage(sequence, provider, model, safeEndpoint,
                runId, profile, turn, "IN_FLIGHT", null, false, null, null, null, null, null, metrics);
        int index = task == null ? -1 : task.add(started);
        LiveTestDiagnostics.recordRequestUsage(started);
        return new RequestHandle(task, index, started);
    }

    public static ModelRequestUsage complete(RequestHandle handle, Integer httpStatus,
                                             ProviderTokenUsage providerUsage) {
        return finish(handle, "SUCCEEDED", httpStatus, providerUsage);
    }

    public static ModelRequestUsage fail(RequestHandle handle, Integer httpStatus) {
        return finish(handle, "FAILED", httpStatus, ProviderTokenUsage.unknown());
    }

    private static ModelRequestUsage finish(RequestHandle handle, String status, Integer httpStatus,
                                            ProviderTokenUsage usage) {
        if (!handle.finished.compareAndSet(false, true)) return handle.started;
        ModelRequestUsage result = handle.started.withResult(status, httpStatus, usage);
        if (handle.task != null) handle.task.replace(handle.index, result);
        LiveTestDiagnostics.recordRequestUsage(result);
        return result;
    }

    public static ModelRequestUsageSummary finishTask(String runId) {
        TaskCollector task = ACTIVE.get();
        if (task == null || !task.runId.equals(runId)) return ModelRequestUsageSummary.empty();
        ACTIVE.remove();
        return ModelRequestUsageSummary.from(task.snapshot());
    }

    public static final class RequestHandle {
        private final TaskCollector task;
        private final int index;
        private final ModelRequestUsage started;
        private final AtomicBoolean finished = new AtomicBoolean();

        private RequestHandle(TaskCollector task, int index, ModelRequestUsage started) {
            this.task = task;
            this.index = index;
            this.started = started;
        }
    }

    private static final class TaskCollector {
        private final String runId;
        private final String profile;
        private final long agentTurn;
        private final List<ModelRequestUsage> requests = new ArrayList<>();

        private TaskCollector(String runId, String profile, long agentTurn) {
            this.runId = runId;
            this.profile = profile;
            this.agentTurn = agentTurn;
        }

        private synchronized int add(ModelRequestUsage request) {
            requests.add(request);
            return requests.size() - 1;
        }

        private synchronized void replace(int index, ModelRequestUsage request) {
            if (index >= 0 && index < requests.size()) requests.set(index, request);
        }

        private synchronized List<ModelRequestUsage> snapshot() { return List.copyOf(requests); }
    }

    private static String safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return "[INVALID_ENDPOINT]";
        }
    }
}
