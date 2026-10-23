package com.clara.model.video;

import com.clara.core.media.VideoGenerationRequest;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.credential.ProviderCredentialConfiguration;
import com.clara.kernel.http.TransportConfig;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ArkVideoTaskClient} 的边界用例：轮询超时、缺失产物、非法配置与任务标识拼装。
 *
 * <p>视频生成成本高，任何「平台已受理但本地判定异常」的分支都必须把任务号透出；
 * 同时提示词参数缺失时不能静默拼出半截命令行。
 */
public class ArkVideoTaskClientEdgeTest {

    @Test
    public void rejectsNullConfigurationOrTransport() {
        assertThrows(IllegalArgumentException.class, () -> new ArkVideoTaskClient(null, new FakeTransport()));
        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            assertThrows(IllegalArgumentException.class, () -> new ArkVideoTaskClient(configuration, null));
        }
    }

    @Test
    public void rejectsInvalidPollingParameters() {
        Map<String, Object> notNumber = parameters();
        notNumber.put("pollIntervalMillis", "fast");
        try (ProviderConfiguration configuration = configuration(notNumber, "%7Bid%7D")) {
            assertThrows(IllegalArgumentException.class, () -> new ArkVideoTaskClient(configuration, new FakeTransport()));
        }

        Map<String, Object> negative = parameters();
        negative.put("maxPollAttempts", 0);
        try (ProviderConfiguration configuration = configuration(negative, "%7Bid%7D")) {
            assertThrows(IllegalArgumentException.class, () -> new ArkVideoTaskClient(configuration, new FakeTransport()));
        }
    }

    @Test
    public void rejectsSubmissionWithoutTaskId() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"status\":\"queued\"}"));

        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, transport)
                            .generate(new VideoGenerationRequest("cat", null, null)));
            assertTrue(failure.getMessage().contains("任务标识"));
        }
    }

    @Test
    public void reportsSuccessWithoutVideoUrl() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"id\":\"t-1\"}"));
        transport.respond(json(200, "{\"status\":\"succeeded\",\"error\":{\"message\":\"no output\"}}"));

        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, transport)
                            .generate(new VideoGenerationRequest("cat", null, null)));
            assertTrue(failure.getMessage().contains("t-1"));
            assertTrue(failure.getMessage().contains("no output"));
        }
    }

    @Test
    public void timesOutAfterConfiguredAttemptsAndReportsProgress() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"id\":\"t-2\"}"));
        transport.respond(json(200, "{\"status\":\"running\"}"));
        transport.respond(json(200, "{\"status\":\"running\"}"));
        transport.respond(json(200, "{\"status\":\"running\"}"));
        List<String> progress = new ArrayList<>();

        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, transport)
                            .generate(new VideoGenerationRequest("cat", null, progress::add)));
            assertTrue(failure.getMessage().contains("轮询超时"));
            assertTrue(failure.getMessage().contains("running"));
        }
        assertTrue(progress.get(0).contains("已提交"));
        // 状态未变化时不重复提示，避免刷屏。
        assertEquals(2, progress.size());
        assertTrue(progress.get(1).contains("running"));
    }

    @Test
    public void appendsTaskIdWhenTemplateHasNoPlaceholder() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"id\":\"t 3/4\"}"));
        transport.respond(json(200, "{\"status\":\"succeeded\",\"content\":[{\"video_url\":\"https://x/v.mp4\"}]}"));

        try (ProviderConfiguration configuration = configuration(parameters(), "/tasks/")) {
            new ArkVideoTaskClient(configuration, transport)
                    .generate(new VideoGenerationRequest("cat", null, null));
        }

        RawHttpRequest query = transport.requests.get(1);
        assertTrue(query.getEndpoint().toString().endsWith("/tasks/t%203%2F4"));
    }

    @Test
    public void surfacesTopLevelMessageAndRawBodyFallback() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(500, "{\"message\":\"upstream boom\"}"));
        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, transport)
                            .generate(new VideoGenerationRequest("cat", null, null)));
            assertTrue(failure.getMessage().contains("upstream boom"));
        }

        FakeTransport rawBody = new FakeTransport();
        rawBody.respond(json(502, "{\"unexpected\":\"gateway\"}"));
        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, rawBody)
                            .generate(new VideoGenerationRequest("cat", null, null)));
            assertTrue(failure.getMessage().contains("gateway"));
        }
    }

    @Test
    public void rejectsEmptyBodyAndMalformedJson() {
        FakeTransport empty = new FakeTransport();
        empty.respond(json(200, ""));
        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, empty)
                            .generate(new VideoGenerationRequest("cat", null, null)));
            assertTrue(failure.getMessage().contains("空响应"));
        }

        FakeTransport malformed = new FakeTransport();
        malformed.respond(json(200, "{not-json"));
        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, malformed)
                            .generate(new VideoGenerationRequest("cat", null, null)));
            assertTrue(failure.getMessage().contains("不是合法 JSON"));
        }
    }

    @Test
    public void skipsBlankReferencesAndKeepsFirstFrameRole() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"id\":\"t-4\"}"));
        transport.respond(json(200, "{\"status\":\"success\",\"data\":[{\"url\":\"https://x/a.mp4\"}]}"));

        try (ProviderConfiguration configuration = configuration(parameters(), "%7Bid%7D")) {
            new ArkVideoTaskClient(configuration, transport).generate(new VideoGenerationRequest(
                    "cat", java.util.Arrays.asList("  ", "https://x/ref.png"), null));
        }

        String body = new String(transport.requests.get(0).getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"role\":\"first_frame\""));
    }

    private static Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pollIntervalMillis", 1);
        parameters.put("maxPollAttempts", 3);
        parameters.put("ratio", "16:9");
        parameters.put("duration", 5);
        return parameters;
    }

    private static ProviderConfiguration configuration(Map<String, Object> parameters, String taskPath) {
        ProviderCredentialConfiguration credential = new ProviderCredentialConfiguration(
                "volcengine", null, "test-key".toCharArray());
        TransportConfig transport = new TransportConfig(
                Collections.singleton("https://ark.cn-beijing.volces.com"),
                1000, 1000, 1 << 20, 1 << 20, false);
        ProviderCapabilityConfiguration video = new ProviderCapabilityConfiguration(
                URI.create("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks"),
                URI.create("https://ark.cn-beijing.volces.com/api/v3/contents/generations" + taskPath),
                "doubao-seedance-2-0-mini-260615", "ark-v3-content-tasks", null, parameters, null);
        Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities = new LinkedHashMap<>();
        capabilities.put(CapabilityKindEnum.VIDEO, video);
        return new ProviderConfiguration(ModelProviderEnum.VOLCENGINE, credential, transport, capabilities);
    }

    private static RawHttpResponse json(int status, String body) {
        return new RawHttpResponse(status, Collections.<String, List<String>>emptyMap(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeTransport implements IHttpTransport {
        private final Deque<RawHttpResponse> responses = new ArrayDeque<>();
        private final List<RawHttpRequest> requests = new ArrayList<>();

        private void respond(RawHttpResponse response) {
            responses.add(response);
        }

        @Override
        public IHttpCall newCall(RawHttpRequest request, com.clara.core.transport.HttpTransportPolicy policy,
                IExecutionGuard guard) {
            return new IHttpCall() {
                private boolean cancelled;

                @Override
                public RawHttpResponse execute() {
                    requests.add(request);
                    return responses.removeFirst();
                }

                @Override
                public RawHttpResponse execute(ISseEventListener listener) {
                    return execute();
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public void close() {
                    // 测试调用无连接需释放
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }
    }
}