package com.agent.llm;

import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeUnit;

/** Process-wide hard request/time gate used only when explicitly enabled for one live test. */
public final class LiveTestControl {
    public static final int MAX_REQUEST_ATTEMPTS = 8;
    public static final Duration MAX_TASK_DURATION = Duration.ofMinutes(5);
    private static final Object LOCK = new Object();
    private static volatile LiveTestControl processInstance;

    private final long maxDurationNanos;
    private long startedAtNanos;
    private int attempts;
    private final IdentityHashMap<okhttp3.Call, Integer> exchangesByCall = new IdentityHashMap<>();

    public LiveTestControl() {
        this(MAX_REQUEST_ATTEMPTS, MAX_TASK_DURATION);
    }

    LiveTestControl(int maxAttempts, Duration maxDuration) {
        if (maxAttempts < 1 || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("Live test limits must be positive");
        }
        this.attemptLimit = maxAttempts;
        this.maxDurationNanos = maxDuration.toNanos();
    }

    private final int attemptLimit;

    static void resetProcessInstanceForTests() {
        clearProcessInstanceForTests();
    }

    /** Returns the one process-wide gate when the explicit live-test switch is enabled. */
    public static LiveTestControl processInstanceIfEnabled() {
        if (!enabled()) return null;
        LiveTestControl current = processInstance;
        if (current != null) return current;
        synchronized (LOCK) {
            if (processInstance == null) processInstance = new LiveTestControl();
            return processInstance;
        }
    }

    public static boolean enabled() {
        String value = System.getProperty("agent.live-test-mode");
        if (value == null) value = System.getenv("AGENT_LIVE_TEST_MODE");
        return Boolean.parseBoolean(value);
    }

    /** Starts the one-task wall-clock window at the beginning of its Agent turn. */
    public synchronized void beginTask() {
        if (startedAtNanos == 0) startedAtNanos = System.nanoTime();
    }

    /** Reserves exactly one outgoing HTTP request attempt before connection setup. */
    public synchronized int beforeExchange(okhttp3.Call call, String endpoint) throws IOException {
        long now = System.nanoTime();
        if (startedAtNanos == 0) startedAtNanos = now;
        long elapsed = now - startedAtNanos;
        if (elapsed >= maxDurationNanos) {
            throw new LiveTestControlException(LiveTestControlException.Code.TASK_TIMEOUT,
                    "TASK_TIMEOUT: five-minute live-test deadline expired; no further request was sent.");
        }
        if (attempts >= attemptLimit) {
            throw new LiveTestControlException(LiveTestControlException.Code.REQUEST_BUDGET_EXCEEDED,
                    "REQUEST_BUDGET_EXCEEDED: live-test request limit reached; no further request was sent.");
        }
        long remaining = maxDurationNanos - elapsed;
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
        int requestNumber = ++attempts;
        com.agent.diagnostics.LiveTestDiagnostics.recordModelRequest(
                requestNumber, endpoint, "HTTP_EXCHANGE_RESERVED");
        com.agent.diagnostics.LiveTestDiagnostics.assertHealthy();
        return requestNumber;
    }

    synchronized void reserveCall(okhttp3.Call call, String endpoint) throws IOException {
        beforeExchange(call, endpoint);
        exchangesByCall.put(call, 0);
    }

    synchronized void beforeNetworkExchange(okhttp3.Call call, String endpoint) throws IOException {
        Integer exchanges = exchangesByCall.get(call);
        if (exchanges == null) {
            beforeExchange(call, endpoint);
            exchangesByCall.put(call, 1);
        } else if (exchanges == 0) {
            exchangesByCall.put(call, 1);
        } else {
            beforeExchange(call, endpoint);
            exchangesByCall.put(call, exchanges + 1);
        }
    }

    synchronized void finishCall(okhttp3.Call call) {
        exchangesByCall.remove(call);
    }

    public synchronized int attempts() {
        return attempts;
    }

    public synchronized long remainingNanos() {
        if (startedAtNanos == 0) return maxDurationNanos;
        return Math.max(0, maxDurationNanos - (System.nanoTime() - startedAtNanos));
    }

    /**
     * Bounds child-process verification by the remaining task budget, keeping ten seconds
     * for process-tree termination and output-reader shutdown in DefaultProcessRunner.
     * Returns null when live-test mode is disabled and ZERO when the safe window is exhausted.
     */
    public static Duration boundedProcessTimeout(Duration configured) {
        LiveTestControl control = processInstanceIfEnabled();
        if (control == null) return null;
        return control.boundProcessTimeout(configured);
    }

    synchronized Duration boundProcessTimeout(Duration configured) {
        long safeRemaining = remainingNanos() - TimeUnit.SECONDS.toNanos(10);
        if (safeRemaining <= 0) return Duration.ZERO;
        Duration safeLimit = Duration.ofNanos(safeRemaining);
        return configured.compareTo(safeLimit) <= 0 ? configured : safeLimit;
    }

    public synchronized void configureCallTimeout(okhttp3.Call call) throws IOException {
        long now = System.nanoTime();
        if (startedAtNanos == 0) startedAtNanos = now;
        long remaining = maxDurationNanos - (now - startedAtNanos);
        if (remaining <= 0) {
            throw new LiveTestControlException(LiveTestControlException.Code.TASK_TIMEOUT,
                    "TASK_TIMEOUT: five-minute live-test deadline expired; no further request was sent.");
        }
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
    }

    /** Application interceptor: reserves before DNS/connect, so failed connects consume budget too. */
    public static Interceptor requestAttemptInterceptor(LiveTestControl control) {
        return chain -> {
            control.reserveCall(chain.call(), chain.request().url().toString());
            try {
                return chain.proceed(chain.request());
            } finally {
                control.finishCall(chain.call());
            }
        };
    }

    /** Guards hidden OkHttp follow-ups (including non-redirect status retries) per network exchange. */
    public static Interceptor networkExchangeInterceptor(LiveTestControl control) {
        return chain -> {
            control.beforeNetworkExchange(chain.call(), chain.request().url().toString());
            Response response = chain.proceed(chain.request());
            com.agent.diagnostics.LiveTestDiagnostics.recordProviderHttpResponse(response.code());
            return response;
        };
    }

    static void clearProcessInstanceForTests() {
        synchronized (LOCK) {
            processInstance = null;
        }
    }
}
