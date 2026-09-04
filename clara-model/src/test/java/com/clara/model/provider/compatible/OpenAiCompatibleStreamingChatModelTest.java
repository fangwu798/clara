package com.clara.model.provider.compatible;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.Content;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.Role;
import com.clara.model.api.StreamEvent;
import com.clara.model.api.StreamHandle;
import com.clara.model.api.StreamObserver;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderSseEvent;
import com.clara.model.provider.http.ProviderSseListener;
import com.clara.model.provider.http.ProviderSseTransport;
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

public class OpenAiCompatibleStreamingChatModelTest {
    private ExecutorService executor;

    @Before public void createExecutor() { executor = Executors.newSingleThreadExecutor(); }
    @After public void stopExecutor() { executor.shutdownNow(); }

    @Test public void streamsStructuredDeltasToolsAndFinalResponse() throws Exception {
        String first = ("{\"id\":\"r1\",\"model\":\"fixed-model\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"Hi\",\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"lookup\",\"arguments\":\"ARG\"}}]},\"finish_reason\":null}]}")
                .replace("ARG", "{");
        String second = ("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"ARG\"}}]},"
                        + "\"finish_reason\":null}]}").replace("ARG", "\\\"x\\\":1}");
        FakeTransport transport = new FakeTransport(200, first, second,
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}",
                "[DONE]");
        RecordingObserver observer = new RecordingObserver();
        StreamHandle handle = model(transport, secret()).chat(request(), observer);

        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS));
        assertDone(handle);
        assertEquals(5, observer.events.size());
        assertEquals(StreamEvent.Type.TEXT_DELTA, observer.events.get(0).getType());
        assertEquals("Hi", observer.events.get(0).getPayload());
        assertEquals(StreamEvent.Type.TOOL_ARGUMENT_DELTA, observer.events.get(1).getType());
        assertEquals("call-1", observer.events.get(1).getToolCallId());
        assertEquals("lookup", observer.events.get(1).getToolName());
        assertEquals(StreamEvent.Type.TOOL_CALL_COMPLETE, observer.events.get(3).getType());
        assertEquals(StreamEvent.Type.RESPONSE_COMPLETE, observer.events.get(4).getType());
        for (int i = 0; i < observer.events.size(); i++)
            assertEquals(i + 1L, observer.events.get(i).getSequence());
        StreamEvent complete = observer.events.get(4);
        assertNotNull(complete.getResponse());
        assertEquals(5, complete.getResponse().getTokenUsage().getTotalTokens());
        ToolCallContent call = (ToolCallContent) complete.getResponse().getMessage()
                .getContents().get(1);
        assertEquals(1, new ObjectMapper().readTree(call.getArguments()).get("x").asInt());
        assertEquals("Bearer secret-value", transport.request.getHeaders().get("Authorization"));
        assertEquals("text/event-stream", transport.request.getHeaders().get("Accept"));
        assertTrue(new ObjectMapper().readTree(transport.request.getBody()).get("stream").asBoolean());
    }

    @Test public void emitsSafeProviderAndCredentialErrors() throws Exception {
        RecordingObserver providerObserver = new RecordingObserver();
        model(new FakeTransport(429), secret()).chat(request(), providerObserver);
        assertTrue(providerObserver.terminal.await(3, TimeUnit.SECONDS));
        StreamEvent providerError = providerObserver.events.get(0);
        assertEquals(StreamEvent.Type.ERROR, providerError.getType());
        assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, providerError.getPayload());
        assertEquals("429", providerError.getError().getProviderCode());
        assertTrue(providerError.getError().isRetryable());

        RecordingObserver credentialObserver = new RecordingObserver();
        model(new FakeTransport(200), new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) { return null; }
        }).chat(request(), credentialObserver);
        assertTrue(credentialObserver.terminal.await(3, TimeUnit.SECONDS));
        assertEquals(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE,
                credentialObserver.events.get(0).getError().getCode());
    }

    @Test public void treatsEofBeforeDoneAsProtocolFailure() throws Exception {
        FakeTransport transport = new FakeTransport(200,
                "{\"choices\":[{\"delta\":{\"content\":\"partial\"},"
                        + "\"finish_reason\":\"stop\"}]}");
        RecordingObserver observer = new RecordingObserver();
        model(transport, secret()).chat(request(), observer);
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS));
        assertEquals(StreamEvent.Type.TEXT_DELTA, observer.events.get(0).getType());
        assertEquals(StreamEvent.Type.ERROR, observer.events.get(1).getType());
        assertEquals(ErrorCodes.MODEL_PROTOCOL_ERROR, observer.events.get(1).getError().getCode());
    }

    @Test public void handleCancelsInvocationAndTerminatesStream() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        RecordingObserver observer = new RecordingObserver();
        StreamHandle handle = model(transport, secret()).chat(request(), observer);
        assertTrue(transport.started.await(3, TimeUnit.SECONDS));
        assertTrue(handle.cancel());
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS));
        assertEquals(ErrorCodes.INVOCATION_CANCELLED, observer.events.get(0).getError().getCode());
        assertDone(handle);
    }

    @Test public void validatesRequestBeforeScheduling() {
        final OpenAiCompatibleStreamingChatModel model = model(new FakeTransport(200), secret());
        try {
            model.chat(null, new RecordingObserver());
            fail("expected invalid request");
        } catch (IllegalArgumentException expected) { }
    }

    private OpenAiCompatibleStreamingChatModel model(ProviderSseTransport transport,
            ProviderCredentialResolver resolver) {
        try {
            return new OpenAiCompatibleStreamingChatModel(new OpenAiCompatibleChatConfig(
                    ModelProvider.OPENAI, new URI("https://provider.example/v1/chat/completions"),
                    "fixed-model", "chat-completions-v1", "secret-ref"), transport,
                    resolver, executor, 8, 10000);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static ProviderCredentialResolver secret() {
        return new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) {
                return "secret-value".toCharArray();
            }
        };
    }

    private static ChatRequest request() {
        return new ChatRequest(Collections.singletonList(new ChatMessage(Role.USER,
                Collections.<Content>singletonList(new TextContent("hello")), null, null)),
                null, null, null, new InvocationContext("tenant", null, null, "trace",
                        System.currentTimeMillis() + 5000, new CancellationToken()));
    }

    private static void assertDone(StreamHandle handle) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000L;
        while (!handle.isDone() && System.currentTimeMillis() < end) Thread.sleep(5L);
        assertTrue(handle.isDone());
    }

    private static final class RecordingObserver implements StreamObserver {
        private final List<StreamEvent> events = Collections.synchronizedList(
                new ArrayList<StreamEvent>());
        private final CountDownLatch terminal = new CountDownLatch(1);
        public void onEvent(StreamEvent event) {
            events.add(event);
            if (event.getType() == StreamEvent.Type.RESPONSE_COMPLETE
                    || event.getType() == StreamEvent.Type.ERROR) terminal.countDown();
        }
    }

    private static final class FakeTransport implements ProviderSseTransport {
        private final int status;
        private final String[] chunks;
        private ProviderHttpRequest request;
        private FakeTransport(int status, String... chunks) {
            this.status = status;
            this.chunks = chunks;
        }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                ProviderSseListener listener) {
            this.request = request;
            if (status >= 200 && status < 300) {
                for (String chunk : chunks)
                    if (!listener.onEvent(newEvent(chunk))) break;
            }
            return new ProviderHttpResponse(status, null,
                    "secret provider diagnostic".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class BlockingTransport implements ProviderSseTransport {
        private final CountDownLatch started = new CountDownLatch(1);
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                ProviderSseListener listener) {
            started.countDown();
            while (true) {
                context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
                try { Thread.sleep(10L); }
                catch (InterruptedException ignored) { }
            }
        }
    }

    private static ProviderSseEvent newEvent(String data) {
        return new ProviderSseEvent("message", data, null, null);
    }
}
