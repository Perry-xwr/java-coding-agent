package com.agent.llm;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LiveTestControlTest {
    @AfterEach
    void cleanup() {
        System.clearProperty("agent.live-test-mode");
        LiveTestControl.resetProcessInstanceForTests();
    }

    @Test
    void recreatedFactoryClientsShareEightExchangeBudgetAndNinthIsBlockedBeforeProceed() throws Exception {
        System.setProperty("agent.live-test-mode", "true");
        GlmClient first = glmClient("http://model.test/v4");
        GlmClient recreated = glmClient("http://model.test/v4");
        LiveTestControl control = LiveTestControl.processInstanceIfEnabled();
        assertSame(control, first.liveTestControlForTesting());
        assertSame(control, recreated.liveTestControlForTesting());
        Interceptor interceptor = LiveTestControl.requestAttemptInterceptor(control);
        AtomicInteger exchanges = new AtomicInteger();
        for (int index = 0; index < 8; index++) {
            FakeChain chain = new FakeChain(exchanges, null);
            assertEquals(200, interceptor.intercept(chain).code());
        }
        FakeChain ninth = new FakeChain(exchanges, null);
        IOException capped = assertThrows(IOException.class, () -> interceptor.intercept(ninth));
        assertTrue(capped.getMessage().contains("REQUEST_BUDGET_EXCEEDED"));
        assertEquals(8, exchanges.get());
        assertEquals(8, control.attempts());
    }

    @Test
    void liveClientDisablesTransportRetriesAndRedirectFollowups() {
        System.setProperty("agent.live-test-mode", "true");
        GlmClient client = glmClient("http://model.test/v4");
        assertFalse(client.httpClientForTesting().retryOnConnectionFailure());
        assertFalse(client.httpClientForTesting().followRedirects());
        assertFalse(client.httpClientForTesting().followSslRedirects());
        assertSame(Authenticator.NONE, client.httpClientForTesting().authenticator());
        assertSame(Authenticator.NONE, client.httpClientForTesting().proxyAuthenticator());
    }

    @Test
    void connectionFailureConsumesExactlyOneExchangeWithoutAnOkHttpRetry() throws Exception {
        LiveTestControl control = new LiveTestControl(8, Duration.ofSeconds(2));
        AtomicInteger exchanges = new AtomicInteger();
        FakeChain chain = new FakeChain(exchanges, new IOException("connection refused"));
        assertThrows(IOException.class, () -> LiveTestControl.requestAttemptInterceptor(control).intercept(chain));
        assertEquals(1, exchanges.get());
        assertEquals(1, control.attempts());
    }

    @Test
    void expiredTaskDeadlineBlocksBeforeAnyFurtherExchange() throws Exception {
        LiveTestControl control = new LiveTestControl(8, Duration.ofMillis(30));
        control.beginTask();
        Thread.sleep(60);
        AtomicInteger exchanges = new AtomicInteger();
        FakeChain chain = new FakeChain(exchanges, null);
        IOException failure = assertThrows(IOException.class,
                () -> LiveTestControl.requestAttemptInterceptor(control).intercept(chain));
        assertTrue(failure.getMessage().contains("TASK_TIMEOUT"));
        assertEquals(0, exchanges.get());
        assertEquals(0, control.attempts());
    }

    @Test
    void childProcessTimeoutIsCappedByRemainingTaskDeadline() throws Exception {
        LiveTestControl control = new LiveTestControl(8, Duration.ofMillis(100));
        control.beginTask();
        assertEquals(Duration.ZERO, control.boundProcessTimeout(Duration.ofSeconds(15)));
    }

    @Test
    void hiddenFollowUpExchangeConsumesAnotherBudgetSlot() throws Exception {
        LiveTestControl control = new LiveTestControl(1, Duration.ofSeconds(2));
        AtomicInteger exchanges = new AtomicInteger();
        FakeChain chain = new FakeChain(exchanges, null);
        control.reserveCall(chain.call(), chain.request().url().toString());
        Interceptor networkGuard = LiveTestControl.networkExchangeInterceptor(control);
        assertEquals(200, networkGuard.intercept(chain).code());
        IOException capped = assertThrows(IOException.class, () -> networkGuard.intercept(chain));
        assertTrue(capped.getMessage().contains("REQUEST_BUDGET_EXCEEDED"));
        assertEquals(1, exchanges.get());
        assertEquals(1, control.attempts());
        control.finishCall(chain.call());
    }

    private static GlmClient glmClient(String baseUrl) {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "glm", "MODEL_BASE_URL", baseUrl,
                "MODEL_NAME", "test-model", "GLM_API_KEY", "unit-test-secret"));
        return (GlmClient) LlmClientFactory.create(config);
    }

    private static final class FakeChain implements Interceptor.Chain {
        private final AtomicInteger exchanges;
        private final IOException failure;
        private final Request request = new Request.Builder().url("http://model.test/v4/chat/completions").build();
        private final FakeCall call = new FakeCall(request);

        FakeChain(AtomicInteger exchanges, IOException failure) {
            this.exchanges = exchanges;
            this.failure = failure;
        }

        @Override public Request request() { return request; }
        @Override public Response proceed(Request request) throws IOException {
            exchanges.incrementAndGet();
            if (failure != null) throw failure;
            return new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").build();
        }
        @Override public Connection connection() { return null; }
        @Override public Call call() { return call; }
        @Override public int connectTimeoutMillis() { return 1000; }
        @Override public Interceptor.Chain withConnectTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
        @Override public int readTimeoutMillis() { return 1000; }
        @Override public Interceptor.Chain withReadTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
        @Override public int writeTimeoutMillis() { return 1000; }
        @Override public Interceptor.Chain withWriteTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
    }

    private static final class FakeCall implements Call {
        private final Request request;
        private final Timeout timeout = new Timeout();
        FakeCall(Request request) { this.request = request; }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new UnsupportedOperationException(); }
        @Override public void enqueue(Callback responseCallback) { throw new UnsupportedOperationException(); }
        @Override public void cancel() { }
        @Override public boolean isExecuted() { return false; }
        @Override public boolean isCanceled() { return false; }
        @Override public Timeout timeout() { return timeout; }
        @Override public Call clone() { return new FakeCall(request); }
    }
}
