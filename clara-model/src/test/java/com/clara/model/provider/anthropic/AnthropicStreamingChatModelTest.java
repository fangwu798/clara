package com.clara.model.provider.anthropic;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.Content;
import com.clara.model.api.Role;
import com.clara.model.api.StreamEvent;
import com.clara.model.api.StreamHandle;
import com.clara.model.api.StreamObserver;
import com.clara.model.api.TextContent;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.event.ProviderSseEvent;
import com.clara.model.provider.http.ProviderSseListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnthropicStreamingChatModelTest {
    private ExecutorService executor;
    @Before public void start() { executor = Executors.newSingleThreadExecutor(); }
    @After public void stop() { executor.shutdownNow(); }

    @Test public void streamsTextAndStructuredCompletion() throws Exception {
        FakeTransport transport = new FakeTransport(200,
                event("message_start", AnthropicStreamAccumulatorTest.start()),
                event("content_block_start", AnthropicStreamAccumulatorTest.textStart(0)),
                event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}"),
                event("content_block_stop", AnthropicStreamAccumulatorTest.stopBlock(0)),
                event("message_delta", AnthropicStreamAccumulatorTest.messageDelta("end_turn", 3)),
                event("message_stop", "{\"type\":\"message_stop\"}"));
        Observer observer = new Observer();
        StreamHandle handle = model(transport).chat(request(), observer);

        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS));
        waitDone(handle);
        assertEquals(2, observer.events.size());
        assertEquals(StreamEvent.Type.TEXT_DELTA, observer.events.get(0).getType());
        assertEquals("hello", observer.events.get(0).getPayload());
        StreamEvent complete = observer.events.get(1);
        assertEquals(StreamEvent.Type.RESPONSE_COMPLETE, complete.getType());
        assertEquals(5, complete.getResponse().getTokenUsage().getTotalTokens());
        assertEquals("secret-value", transport.request.getHeaders().get("x-api-key"));
        assertEquals("2023-06-01", transport.request.getHeaders().get("anthropic-version"));
        assertTrue(new ObjectMapper().readTree(transport.request.getBody()).get("stream").asBoolean());
    }

    @Test public void mapsHttpFailureAndIncompleteStreamToErrors() throws Exception {
        Observer failed = new Observer();
        model(new FakeTransport(503)).chat(request(), failed);
        assertTrue(failed.terminal.await(3, TimeUnit.SECONDS));
        assertEquals("MODEL_PROVIDER_FAILED", failed.events.get(0).getError().getCode());
        assertTrue(failed.events.get(0).getError().isRetryable());

        Observer incomplete = new Observer();
        model(new FakeTransport(200,
                event("message_start", AnthropicStreamAccumulatorTest.start())))
                .chat(request(), incomplete);
        assertTrue(incomplete.terminal.await(3, TimeUnit.SECONDS));
        assertEquals("MODEL_PROTOCOL_ERROR", incomplete.events.get(0).getError().getCode());
    }

    private AnthropicStreamingChatModel model(ProviderHttpTransport transport) throws Exception {
        return new AnthropicStreamingChatModel(new AnthropicChatConfig(
                new URI("https://api.anthropic.example/v1/messages"), "claude-fixed",
                "messages-v1", "2023-06-01", "secret-ref", 128), transport,
                new ProviderCredentialResolver() {
                    public char[] resolve(String tenantId, String secretRef) {
                        return "secret-value".toCharArray();
                    }
                }, executor, 8, 1000);
    }

    private static ChatRequest request() {
        return new ChatRequest(Collections.singletonList(new ChatMessage(Role.USER,
                Collections.<Content>singletonList(new TextContent("hello")), null, null)),
                null, null, null, new InvocationContext("tenant", null, null, "trace",
                        System.currentTimeMillis() + 5000, new CancellationToken()));
    }

    private static ProviderSseEvent event(String name, String data) {
        return new ProviderSseEvent(name, data, null, null);
    }

    private static void waitDone(StreamHandle handle) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000L;
        while (!handle.isDone() && System.currentTimeMillis() < end) Thread.sleep(5L);
        assertTrue(handle.isDone());
    }

    private static final class Observer implements StreamObserver {
        private final List<StreamEvent> events = Collections.synchronizedList(
                new ArrayList<StreamEvent>());
        private final CountDownLatch terminal = new CountDownLatch(1);
        public void onEvent(StreamEvent event) {
            events.add(event);
            if (event.getType() == StreamEvent.Type.RESPONSE_COMPLETE
                    || event.getType() == StreamEvent.Type.ERROR) terminal.countDown();
        }
    }

    private static final class FakeTransport implements ProviderHttpTransport {
        private final int status;
        private final ProviderSseEvent[] events;
        private ProviderHttpRequest request;
        private FakeTransport(int status, ProviderSseEvent... events) {
            this.status = status;
            this.events = events;
        }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context) {
            throw new UnsupportedOperationException("sync call is not used in this test");
        }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                ProviderSseListener listener) {
            this.request = request;
            if (status >= 200 && status < 300)
                for (ProviderSseEvent event : events) if (!listener.onEvent(event)) break;
            return new ProviderHttpResponse(status, null,
                    "secret diagnostic".getBytes(StandardCharsets.UTF_8));
        }
    }
}
